-- ADM 1.4 : fournisseurs, banques, modes de paiement, catégories de dépenses, centres de coût (EF-ADM-04)
-- et paramètres fiscaux et de contrôle datés (EF-ADM-05, EF-ADM-06).

CREATE TABLE fournisseurs
(
    id              BIGSERIAL PRIMARY KEY,
    raison_sociale  VARCHAR(255) NOT NULL,
    pays            VARCHAR(100) NOT NULL,
    devise          VARCHAR(3)   NOT NULL,
    actif           BOOLEAN      NOT NULL DEFAULT TRUE,
    version         BIGINT       NOT NULL DEFAULT 0,
    cree_le         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    modifie_le      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    cree_par        BIGINT REFERENCES utilisateurs (id),
    modifie_par     BIGINT REFERENCES utilisateurs (id),
    CONSTRAINT ck_fournisseurs_devise CHECK (devise ~ '^[A-Z]{3}$')
);

CREATE UNIQUE INDEX ux_fournisseurs_raison_sociale ON fournisseurs (lower(raison_sociale));

CREATE TABLE contacts_fournisseur
(
    id             BIGSERIAL PRIMARY KEY,
    fournisseur_id BIGINT       NOT NULL REFERENCES fournisseurs (id) ON DELETE CASCADE,
    nom            VARCHAR(255) NOT NULL,
    fonction       VARCHAR(255),
    email          VARCHAR(255),
    telephone      VARCHAR(30)
);

CREATE INDEX idx_contacts_fournisseur ON contacts_fournisseur (fournisseur_id);

-- Les trois référentiels à code et libellé ont la même forme ; le code ne change plus une fois créé.
CREATE TABLE banques
(
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(30)  NOT NULL,
    libelle     VARCHAR(255) NOT NULL,
    actif       BOOLEAN      NOT NULL DEFAULT TRUE,
    version     BIGINT       NOT NULL DEFAULT 0,
    cree_le     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    modifie_le  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    cree_par    BIGINT REFERENCES utilisateurs (id),
    modifie_par BIGINT REFERENCES utilisateurs (id)
);

CREATE TABLE categories_depense
(
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(30)  NOT NULL,
    libelle     VARCHAR(255) NOT NULL,
    actif       BOOLEAN      NOT NULL DEFAULT TRUE,
    version     BIGINT       NOT NULL DEFAULT 0,
    cree_le     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    modifie_le  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    cree_par    BIGINT REFERENCES utilisateurs (id),
    modifie_par BIGINT REFERENCES utilisateurs (id)
);

CREATE TABLE centres_cout
(
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(30)  NOT NULL,
    libelle     VARCHAR(255) NOT NULL,
    actif       BOOLEAN      NOT NULL DEFAULT TRUE,
    version     BIGINT       NOT NULL DEFAULT 0,
    cree_le     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    modifie_le  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    cree_par    BIGINT REFERENCES utilisateurs (id),
    modifie_par BIGINT REFERENCES utilisateurs (id)
);

CREATE UNIQUE INDEX ux_banques_code ON banques (upper(code));
CREATE UNIQUE INDEX ux_categories_depense_code ON categories_depense (upper(code));
CREATE UNIQUE INDEX ux_centres_cout_code ON centres_cout (upper(code));

-- Chaque mode de paiement interne se traduit vers la nomenclature de la DGI (EF-FAC-09).
CREATE TABLE modes_paiement
(
    id                       BIGSERIAL PRIMARY KEY,
    code                     VARCHAR(30)  NOT NULL,
    libelle                  VARCHAR(255) NOT NULL,
    type_dgi                 VARCHAR(20)  NOT NULL,
    justificatif             VARCHAR(255),
    justificatif_obligatoire BOOLEAN      NOT NULL DEFAULT TRUE,
    actif                    BOOLEAN      NOT NULL DEFAULT TRUE,
    -- Livré avec l'application : les autres modules s'appuient sur son code, qui ne peut donc pas changer.
    systeme                  BOOLEAN      NOT NULL DEFAULT FALSE,
    version                  BIGINT       NOT NULL DEFAULT 0,
    cree_le                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    modifie_le               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    cree_par                 BIGINT REFERENCES utilisateurs (id),
    modifie_par              BIGINT REFERENCES utilisateurs (id),
    CONSTRAINT ck_modes_paiement_type_dgi CHECK (type_dgi IN
        ('ESPECES', 'VIREMENT', 'CARTEBANCAIRE', 'MOBILEMONEY', 'CHEQUES', 'CREDIT', 'AUTRE'))
);

CREATE UNIQUE INDEX ux_modes_paiement_code ON modes_paiement (upper(code));

INSERT INTO modes_paiement (code, libelle, type_dgi, justificatif, justificatif_obligatoire, systeme)
VALUES ('ESPECES', 'Espèces', 'ESPECES', 'Lettre de décharge', TRUE, TRUE),
       ('CHEQUE', 'Chèque', 'CHEQUES', 'Scan ou photo du chèque, bordereau de dépôt', TRUE, TRUE),
       ('VIREMENT', 'Virement', 'VIREMENT', 'Avis de crédit', TRUE, TRUE),
       ('MOBILE_MONEY', 'Mobile Money', 'MOBILEMONEY', 'Capture ou SMS de confirmation', TRUE, TRUE),
       ('AUTRE', 'Autre', 'AUTRE', 'Pièce justificative', TRUE, TRUE);

-- EF-CPT-06
INSERT INTO categories_depense (code, libelle)
VALUES ('TRANSPORT', 'Transport'),
       ('CARBURANT', 'Carburant'),
       ('ENTRETIEN', 'Entretien'),
       ('LOYER', 'Loyer'),
       ('ELECTRICITE', 'Électricité'),
       ('COMMUNICATION', 'Communication'),
       ('DOUANE_TRANSIT', 'Douane et transit'),
       ('ACHATS', 'Achats'),
       ('FOURNITURES', 'Fournitures'),
       ('MISSIONS', 'Missions'),
       ('SALAIRES_CHARGES', 'Salaires et charges'),
       ('AUTRES', 'Autres');

-- Une valeur de paramètre vaut à partir de sa date d'effet ; l'historique est conservé (jamais de mise à jour en place
-- d'une version passée), car les calculs d'hier doivent rester explicables.
CREATE TABLE parametres
(
    id         BIGSERIAL PRIMARY KEY,
    cle        VARCHAR(100) NOT NULL,
    valeur     VARCHAR(500) NOT NULL,
    date_effet DATE         NOT NULL,
    cree_le    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    cree_par   BIGINT REFERENCES utilisateurs (id),
    CONSTRAINT ux_parametres_cle_date UNIQUE (cle, date_effet)
);
