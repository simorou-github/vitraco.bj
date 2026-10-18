-- Socle technique (SRS 1.0 : §4.5, EF-ADM-07, EF-AUD-01/03/05/10).

-- Trigger réutilisable : table en ajout seul (journal_audit, puis mouvement_stock,
-- mouvement_tresorerie, certification_fiscale). Aucun UPDATE, DELETE ni TRUNCATE.
CREATE FUNCTION interdire_modification() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'La table % est en ajout seul : % interdit', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END
$$;

-- ---------------------------------------------------------------------------
-- Journal d'audit : ajout seul, chaîné par empreinte SHA-256 (EF-AUD-10).
-- ---------------------------------------------------------------------------
CREATE TABLE journal_audit
(
    id                  BIGSERIAL PRIMARY KEY,
    rang                BIGINT       NOT NULL UNIQUE,
    date_heure          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    utilisateur_id      BIGINT,
    utilisateur_libelle VARCHAR(255),
    action              VARCHAR(100) NOT NULL,
    module              VARCHAR(10)  NOT NULL,
    objet_type          VARCHAR(100),
    objet_id            VARCHAR(100),
    ancienne_valeur     JSONB,
    nouvelle_valeur     JSONB,
    motif               TEXT,
    adresse_ip          VARCHAR(45),
    hash_precedent      CHAR(64),
    hash                CHAR(64)     NOT NULL
);

CREATE INDEX idx_journal_audit_objet ON journal_audit (objet_type, objet_id);
CREATE INDEX idx_journal_audit_utilisateur ON journal_audit (utilisateur_id, date_heure);
CREATE INDEX idx_journal_audit_module_date ON journal_audit (module, date_heure);

-- Formule unique de l'empreinte, partagée par le chaînage et la vérification.
CREATE FUNCTION journal_audit_calculer_hash(precedent TEXT, r journal_audit) RETURNS TEXT
    LANGUAGE sql STABLE AS
$$
SELECT encode(sha256(convert_to(concat_ws('|',
                                          coalesce(precedent, ''),
                                          r.rang::text,
                                          to_char(r.date_heure AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US'),
                                          coalesce(r.utilisateur_id::text, ''),
                                          r.action,
                                          r.module,
                                          coalesce(r.objet_type, ''),
                                          coalesce(r.objet_id, ''),
                                          coalesce(r.ancienne_valeur::text, ''),
                                          coalesce(r.nouvelle_valeur::text, ''),
                                          coalesce(r.motif, ''),
                                          coalesce(r.adresse_ip, '')), 'UTF8')), 'hex')
$$;

CREATE FUNCTION journal_audit_chainer() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    derniere journal_audit;
BEGIN
    -- Sérialise les insertions : le rang et le chaînage doivent suivre l'ordre de validation.
    PERFORM pg_advisory_xact_lock(hashtext('journal_audit'));

    SELECT * INTO derniere FROM journal_audit ORDER BY rang DESC LIMIT 1;

    NEW.rang := coalesce(derniere.rang, 0) + 1;
    NEW.hash_precedent := derniere.hash;
    NEW.hash := journal_audit_calculer_hash(derniere.hash, NEW);
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_journal_audit_chainer
    BEFORE INSERT
    ON journal_audit
    FOR EACH ROW
EXECUTE FUNCTION journal_audit_chainer();

CREATE TRIGGER trg_journal_audit_ajout_seul
    BEFORE UPDATE OR DELETE
    ON journal_audit
    FOR EACH ROW
EXECUTE FUNCTION interdire_modification();

CREATE TRIGGER trg_journal_audit_pas_de_truncate
    BEFORE TRUNCATE
    ON journal_audit
    FOR EACH STATEMENT
EXECUTE FUNCTION interdire_modification();

-- Renvoie le rang de la première entrée altérée, ou NULL si la chaîne est intacte.
CREATE FUNCTION journal_audit_premiere_alteration() RETURNS BIGINT
    LANGUAGE plpgsql STABLE AS
$$
DECLARE
    r        journal_audit;
    attendu  TEXT := NULL;
    rang_att BIGINT := 1;
BEGIN
    FOR r IN SELECT * FROM journal_audit ORDER BY rang
        LOOP
            IF r.rang <> rang_att
                OR r.hash_precedent IS DISTINCT FROM attendu
                OR r.hash <> journal_audit_calculer_hash(attendu, r) THEN
                RETURN r.rang;
            END IF;
            attendu := r.hash;
            rang_att := rang_att + 1;
        END LOOP;
    RETURN NULL;
END
$$;

-- ---------------------------------------------------------------------------
-- Numérotation sans trou ni doublon (EF-ADM-07).
-- Une ligne par type de document, emplacement et année. Le verrou de ligne pris
-- par l'incrément est tenu jusqu'à la fin de la transaction du document : une
-- annulation annule aussi l'incrément, donc aucun numéro n'est perdu.
-- ---------------------------------------------------------------------------
CREATE TABLE sequences_numerotation
(
    type_document   VARCHAR(30) NOT NULL,
    emplacement     VARCHAR(30) NOT NULL DEFAULT '',
    annee           SMALLINT    NOT NULL,
    derniere_valeur BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (type_document, emplacement, annee)
);
