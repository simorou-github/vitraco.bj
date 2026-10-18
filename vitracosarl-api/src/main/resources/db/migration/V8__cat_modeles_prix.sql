-- CAT 2.1 : catalogue des modèles et historique des prix (EF-CAT-01, EF-CAT-02, US-010, US-011).

CREATE TABLE modeles
(
    id              BIGSERIAL PRIMARY KEY,
    marque          VARCHAR(100) NOT NULL,
    nom             VARCHAR(255) NOT NULL,
    version_modele  VARCHAR(100),
    -- Clé des imports de colisage et des commandes : unique, insensible à la casse.
    code_article    VARCHAR(50)  NOT NULL,
    description     TEXT,
    -- Groupe de taxation e-MECeF de la moto (A à F) ; vide tant que le groupe n'est pas confirmé par la DGI.
    groupe_taxation VARCHAR(1),
    -- Expression régulière que doit respecter le numéro de châssis normalisé de ce modèle (valeur à fournir par VITRACO).
    format_chassis  VARCHAR(255),
    actif           BOOLEAN      NOT NULL DEFAULT TRUE,
    version         BIGINT       NOT NULL DEFAULT 0,
    cree_le         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    modifie_le      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    cree_par        BIGINT REFERENCES utilisateurs (id),
    modifie_par     BIGINT REFERENCES utilisateurs (id),
    CONSTRAINT ck_modeles_groupe_taxation CHECK (groupe_taxation IN ('A', 'B', 'C', 'D', 'E', 'F'))
);

CREATE UNIQUE INDEX ux_modeles_code_article ON modeles (upper(code_article));
CREATE INDEX idx_modeles_marque ON modeles (marque);

-- Les prix ne vivent que dans l'historique : le prix en vigueur à une date est la dernière valeur dont la date d'effet est
-- atteinte. Table en ajout seul : une erreur se corrige par une nouvelle ligne, jamais en réécrivant le passé, car les
-- documents d'hier doivent rester explicables.
CREATE TABLE prix_historique
(
    id         BIGSERIAL PRIMARY KEY,
    modele_id  BIGINT      NOT NULL REFERENCES modeles (id),
    type_prix  VARCHAR(20) NOT NULL,
    valeur     BIGINT      NOT NULL,
    date_effet DATE        NOT NULL,
    motif      VARCHAR(500),
    cree_le    TIMESTAMPTZ NOT NULL DEFAULT now(),
    cree_par   BIGINT REFERENCES utilisateurs (id),
    CONSTRAINT ck_prix_type CHECK (type_prix IN ('VENTE', 'MINIMUM', 'ACHAT_REFERENCE')),
    CONSTRAINT ck_prix_valeur CHECK (valeur >= 0)
);

CREATE INDEX idx_prix_historique ON prix_historique (modele_id, type_prix, date_effet DESC, id DESC);

CREATE TRIGGER trg_prix_historique_ajout_seul
    BEFORE UPDATE OR DELETE
    ON prix_historique
    FOR EACH ROW
EXECUTE FUNCTION interdire_modification();

CREATE TRIGGER trg_prix_historique_pas_de_truncate
    BEFORE TRUNCATE
    ON prix_historique
    FOR EACH STATEMENT
EXECUTE FUNCTION interdire_modification();
