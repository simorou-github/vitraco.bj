-- CAT 2.2 : châssis, mouvements de stock et corrections de numéro (EF-CAT-04 à EF-CAT-07, RG-01, RG-02, US-012, US-013).

CREATE TABLE chassis
(
    id             BIGSERIAL PRIMARY KEY,
    -- Normalisé : majuscules, sans espace ni tiret. L'unicité porte sur cette forme (RG-01).
    numero         VARCHAR(40) NOT NULL,
    modele_id      BIGINT      NOT NULL REFERENCES modeles (id),
    statut         VARCHAR(30) NOT NULL,
    -- Un seul emplacement courant (RG-02). Seule une moto pas encore reçue n'en a pas : elle n'est ni en magasin ni en boutique.
    emplacement_id BIGINT REFERENCES emplacements (id),
    cout_revient   BIGINT,
    -- Dossier d'arrivage et réception d'origine : les clés étrangères s'ajoutent avec les modules APP et REC.
    arrivage_id    BIGINT,
    reception_id   BIGINT,
    date_entree    DATE,
    numero_moteur  VARCHAR(50),
    couleur        VARCHAR(50),
    version        BIGINT      NOT NULL DEFAULT 0,
    cree_le        TIMESTAMPTZ NOT NULL DEFAULT now(),
    modifie_le     TIMESTAMPTZ NOT NULL DEFAULT now(),
    cree_par       BIGINT REFERENCES utilisateurs (id),
    modifie_par    BIGINT REFERENCES utilisateurs (id),
    CONSTRAINT ck_chassis_numero CHECK (numero ~ '^[A-Z0-9]{3,40}$'),
    CONSTRAINT ck_chassis_statut CHECK (statut IN ('EN_TRANSIT_FOURNISSEUR', 'A_RECEPTIONNER', 'DISPONIBLE', 'RESERVEE', 'EN_PREPARATION',
                                                   'LIVREE', 'EN_TRANSIT_INTERNE', 'DEPOT_VENTE', 'VENDUE_DEPOT_VENTE', 'RETOURNEE',
                                                   'BLOQUEE', 'SORTIE_PAR_AJUSTEMENT')),
    CONSTRAINT ck_chassis_emplacement CHECK (emplacement_id IS NOT NULL OR statut IN ('EN_TRANSIT_FOURNISSEUR', 'A_RECEPTIONNER')),
    CONSTRAINT ck_chassis_cout CHECK (cout_revient IS NULL OR cout_revient >= 0)
);

CREATE UNIQUE INDEX ux_chassis_numero ON chassis (numero);
CREATE INDEX idx_chassis_statut ON chassis (statut);
CREATE INDEX idx_chassis_emplacement ON chassis (emplacement_id);
CREATE INDEX idx_chassis_modele ON chassis (modele_id);
CREATE INDEX idx_chassis_arrivage ON chassis (arrivage_id);

-- Journal des mouvements, en ajout seul : il raconte la vie de chaque moto (fiche châssis) et ne se réécrit jamais.
CREATE TABLE mouvements_stock
(
    id                         BIGSERIAL PRIMARY KEY,
    chassis_id                 BIGINT      NOT NULL REFERENCES chassis (id),
    type                       VARCHAR(40) NOT NULL,
    emplacement_source_id      BIGINT REFERENCES emplacements (id),
    emplacement_destination_id BIGINT REFERENCES emplacements (id),
    statut_avant               VARCHAR(30),
    statut_apres               VARCHAR(30) NOT NULL,
    -- Document d'origine (réception, transfert, livraison…), sans clé étrangère : il relève d'un autre module.
    document_type              VARCHAR(40),
    document_id                BIGINT,
    motif                      VARCHAR(500),
    utilisateur_id             BIGINT REFERENCES utilisateurs (id),
    utilisateur_libelle        VARCHAR(255),
    cree_le                    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_mouvements_chassis ON mouvements_stock (chassis_id, cree_le, id);

CREATE TRIGGER trg_mouvements_stock_ajout_seul
    BEFORE UPDATE OR DELETE
    ON mouvements_stock
    FOR EACH ROW
EXECUTE FUNCTION interdire_modification();

CREATE TRIGGER trg_mouvements_stock_pas_de_truncate
    BEFORE TRUNCATE
    ON mouvements_stock
    FOR EACH STATEMENT
EXECUTE FUNCTION interdire_modification();

-- Une faute de frappe se corrige par une demande motivée qu'un second utilisateur valide ; l'ancien numéro reste recherchable.
CREATE TABLE corrections_numero
(
    id             BIGSERIAL PRIMARY KEY,
    chassis_id     BIGINT       NOT NULL REFERENCES chassis (id),
    ancien_numero  VARCHAR(40)  NOT NULL,
    nouveau_numero VARCHAR(40)  NOT NULL,
    motif          VARCHAR(500) NOT NULL,
    statut         VARCHAR(10)  NOT NULL DEFAULT 'DEMANDEE',
    demande_par    BIGINT       NOT NULL REFERENCES utilisateurs (id),
    demande_le     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decide_par     BIGINT REFERENCES utilisateurs (id),
    decide_le      TIMESTAMPTZ,
    motif_refus    VARCHAR(500),
    CONSTRAINT ck_corrections_statut CHECK (statut IN ('DEMANDEE', 'VALIDEE', 'REFUSEE')),
    CONSTRAINT ck_corrections_decision CHECK ((statut = 'DEMANDEE') = (decide_par IS NULL)),
    -- Le validateur n'est jamais le demandeur (RG-19).
    CONSTRAINT ck_corrections_separation CHECK (decide_par IS NULL OR decide_par <> demande_par),
    CONSTRAINT ck_corrections_numero CHECK (nouveau_numero ~ '^[A-Z0-9]{3,40}$' AND nouveau_numero <> ancien_numero)
);

CREATE UNIQUE INDEX ux_corrections_en_attente ON corrections_numero (chassis_id) WHERE statut = 'DEMANDEE';
CREATE INDEX idx_corrections_ancien ON corrections_numero (ancien_numero) WHERE statut = 'VALIDEE';
