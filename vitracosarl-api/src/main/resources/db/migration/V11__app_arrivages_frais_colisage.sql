-- APP 2.4 : arrivages, frais d'importation et import du colisage (EF-APP-02, EF-APP-03, EF-APP-06, EF-APP-07, US-022, US-023).

CREATE TABLE arrivages
(
    id                  BIGSERIAL PRIMARY KEY,
    commande_id         BIGINT       NOT NULL REFERENCES commandes_fournisseur (id),
    numero_bl           VARCHAR(100) NOT NULL,
    conteneur           VARCHAR(100),
    port                VARCHAR(100),
    date_depart_prevue  DATE,
    date_depart_reelle  DATE,
    date_arrivee_prevue DATE,
    date_arrivee_reelle DATE,
    -- Avancement du dédouanement : il commande l'étape « au port » puis « à réceptionner » de la commande.
    statut_douane       VARCHAR(15)  NOT NULL DEFAULT 'NON_DEMARREE',
    observation         VARCHAR(1000),
    version             BIGINT       NOT NULL DEFAULT 0,
    cree_le             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    modifie_le          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    cree_par            BIGINT REFERENCES utilisateurs (id),
    modifie_par         BIGINT REFERENCES utilisateurs (id),
    CONSTRAINT ck_arrivages_douane CHECK (statut_douane IN ('NON_DEMARREE', 'EN_COURS', 'TERMINEE')),
    CONSTRAINT ck_arrivages_dates_prevues CHECK (date_depart_prevue IS NULL OR date_arrivee_prevue IS NULL OR date_arrivee_prevue >= date_depart_prevue),
    CONSTRAINT ck_arrivages_dates_reelles CHECK (date_depart_reelle IS NULL OR date_arrivee_reelle IS NULL OR date_arrivee_reelle >= date_depart_reelle),
    -- On ne dédouane pas une marchandise qui n'est pas arrivée.
    CONSTRAINT ck_arrivages_douane_arrivee CHECK (statut_douane = 'NON_DEMARREE' OR date_arrivee_reelle IS NOT NULL)
);

CREATE UNIQUE INDEX ux_arrivages_bl ON arrivages (commande_id, upper(numero_bl));
CREATE INDEX idx_arrivages_commande ON arrivages (commande_id);

-- Les châssis annoncés par le colisage pointent vers leur arrivage (colonne posée en 2.2).
ALTER TABLE chassis
    ADD CONSTRAINT fk_chassis_arrivage FOREIGN KEY (arrivage_id) REFERENCES arrivages (id);

-- Chaque frais porte sa devise, son taux et sa pièce (EF-APP-03). Un frais ne se supprime pas : il s'annule, avec un motif.
CREATE TABLE frais_arrivage
(
    id               BIGSERIAL PRIMARY KEY,
    arrivage_id      BIGINT         NOT NULL REFERENCES arrivages (id),
    type             VARCHAR(20)    NOT NULL,
    libelle          VARCHAR(255)   NOT NULL,
    montant          NUMERIC(16, 2) NOT NULL,
    devise           VARCHAR(3)     NOT NULL,
    taux_change      NUMERIC(16, 6) NOT NULL,
    -- Montant converti en francs CFA, arrondi une fois pour toutes à la saisie.
    montant_fcfa     BIGINT         NOT NULL,
    -- N° de facture, de quittance ou de déclaration ; vide = frais signalé « sans pièce ».
    reference_piece  VARCHAR(150),
    date_frais       DATE           NOT NULL,
    annule           BOOLEAN        NOT NULL DEFAULT FALSE,
    motif_annulation VARCHAR(500),
    version          BIGINT         NOT NULL DEFAULT 0,
    cree_le          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    modifie_le       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    cree_par         BIGINT REFERENCES utilisateurs (id),
    modifie_par      BIGINT REFERENCES utilisateurs (id),
    CONSTRAINT ck_frais_type CHECK (type IN ('FRET', 'ASSURANCE', 'DOUANE', 'DROITS_TAXES', 'TRANSIT', 'PORT', 'AUTRE')),
    CONSTRAINT ck_frais_montant CHECK (montant >= 0 AND montant_fcfa >= 0),
    CONSTRAINT ck_frais_taux CHECK (taux_change > 0),
    CONSTRAINT ck_frais_devise CHECK (devise ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_frais_annulation CHECK (NOT annule OR motif_annulation IS NOT NULL)
);

CREATE INDEX idx_frais_arrivage ON frais_arrivage (arrivage_id);

-- Rapport de chaque import de colisage : lignes lues, châssis créés, lignes rejetées avec leur motif (US-023).
CREATE TABLE imports_colisage
(
    id            BIGSERIAL PRIMARY KEY,
    arrivage_id   BIGINT       NOT NULL REFERENCES arrivages (id),
    nom_fichier   VARCHAR(255) NOT NULL,
    nb_lignes     INTEGER      NOT NULL,
    nb_importees  INTEGER      NOT NULL,
    nb_rejetees   INTEGER      NOT NULL,
    rejets        JSONB        NOT NULL DEFAULT '[]',
    cree_le       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    cree_par      BIGINT REFERENCES utilisateurs (id)
);

CREATE INDEX idx_imports_colisage_arrivage ON imports_colisage (arrivage_id);
