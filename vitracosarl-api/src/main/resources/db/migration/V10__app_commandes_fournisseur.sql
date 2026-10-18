-- APP 2.3 : commandes fournisseurs et leur workflow à huit statuts (EF-APP-01, EF-APP-04, EF-APP-08, RG-06, US-020, US-021).
-- Une commande ne touche jamais au stock : seule la réception validée crée du stock (RG-06).

-- Permission spécifique : revenir en arrière sur le statut d'une commande (EF-APP-04).
INSERT INTO permissions (code, libelle, module, action)
VALUES ('approvisionnements.retour_arriere', 'Revenir en arrière sur le statut d''une commande fournisseur', 'approvisionnements', 'retour_arriere')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
         JOIN permissions p ON p.code = 'approvisionnements.retour_arriere'
WHERE r.code = 'direction'
ON CONFLICT DO NOTHING;

CREATE TABLE commandes_fournisseur
(
    id                 BIGSERIAL PRIMARY KEY,
    numero             VARCHAR(40)    NOT NULL,
    fournisseur_id     BIGINT         NOT NULL REFERENCES fournisseurs (id),
    date_commande      DATE           NOT NULL,
    devise             VARCHAR(3)     NOT NULL,
    -- Francs CFA pour une unité de la devise de la commande ; 1 quand la commande est déjà en francs CFA.
    taux_change        NUMERIC(16, 6) NOT NULL,
    incoterm           VARCHAR(3)     NOT NULL,
    -- Base du prix unitaire : FOB (port d'embarquement) ou EXW (départ usine).
    base_prix          VARCHAR(3)     NOT NULL,
    statut             VARCHAR(30)    NOT NULL DEFAULT 'COMMANDEE',
    -- Référence de la proforma du fournisseur, exigée à la confirmation (le dépôt du document viendra avec le module DOC).
    reference_proforma VARCHAR(100),
    observation        VARCHAR(1000),
    version            BIGINT         NOT NULL DEFAULT 0,
    cree_le            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    modifie_le         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    cree_par           BIGINT REFERENCES utilisateurs (id),
    modifie_par        BIGINT REFERENCES utilisateurs (id),
    CONSTRAINT ck_cmd_four_taux CHECK (taux_change > 0),
    CONSTRAINT ck_cmd_four_devise CHECK (devise ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_cmd_four_incoterm CHECK (incoterm IN ('EXW', 'FCA', 'FAS', 'FOB', 'CFR', 'CIF', 'CPT', 'CIP', 'DAP', 'DPU', 'DDP')),
    CONSTRAINT ck_cmd_four_base CHECK (base_prix IN ('FOB', 'EXW')),
    CONSTRAINT ck_cmd_four_statut CHECK (statut IN ('COMMANDEE', 'CONFIRMEE', 'EN_TRANSIT', 'AU_PORT', 'DEDOUANEMENT', 'A_RECEPTIONNER',
                                                    'RECEPTION_PARTIELLE', 'RECEPTIONNEE', 'ANNULEE'))
);

CREATE UNIQUE INDEX ux_cmd_four_numero ON commandes_fournisseur (numero);
CREATE INDEX idx_cmd_four_statut ON commandes_fournisseur (statut);
CREATE INDEX idx_cmd_four_fournisseur ON commandes_fournisseur (fournisseur_id);

CREATE TABLE lignes_commande_fournisseur
(
    id              BIGSERIAL PRIMARY KEY,
    commande_id     BIGINT         NOT NULL REFERENCES commandes_fournisseur (id),
    modele_id       BIGINT         NOT NULL REFERENCES modeles (id),
    quantite        INTEGER        NOT NULL,
    -- Prix unitaire dans la devise de la commande.
    prix_unitaire   NUMERIC(16, 2) NOT NULL,
    -- Mise à jour par les réceptions validées (module REC) ; un surplus accepté peut dépasser la quantité commandée.
    quantite_recue  INTEGER        NOT NULL DEFAULT 0,
    CONSTRAINT ck_lignes_cmd_four_quantite CHECK (quantite > 0),
    CONSTRAINT ck_lignes_cmd_four_prix CHECK (prix_unitaire >= 0),
    CONSTRAINT ck_lignes_cmd_four_recue CHECK (quantite_recue >= 0),
    CONSTRAINT ux_lignes_cmd_four_modele UNIQUE (commande_id, modele_id)
);

-- Passage d'une étape à l'autre, avec sa date et son auteur : jamais réécrit.
CREATE TABLE historique_commande_fournisseur
(
    id                  BIGSERIAL PRIMARY KEY,
    commande_id         BIGINT      NOT NULL REFERENCES commandes_fournisseur (id),
    type                VARCHAR(20) NOT NULL,
    statut_avant        VARCHAR(30),
    statut_apres        VARCHAR(30) NOT NULL,
    motif               VARCHAR(1000),
    utilisateur_id      BIGINT REFERENCES utilisateurs (id),
    utilisateur_libelle VARCHAR(255),
    cree_le             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_hist_cmd_four_type CHECK (type IN ('CREATION', 'AVANCEMENT', 'RETOUR_ARRIERE', 'ANNULATION', 'RECEPTION'))
);

CREATE INDEX idx_hist_cmd_four ON historique_commande_fournisseur (commande_id, cree_le, id);

CREATE TRIGGER trg_hist_cmd_four_ajout_seul
    BEFORE UPDATE OR DELETE
    ON historique_commande_fournisseur
    FOR EACH ROW
EXECUTE FUNCTION interdire_modification();

CREATE TRIGGER trg_hist_cmd_four_pas_de_truncate
    BEFORE TRUNCATE
    ON historique_commande_fournisseur
    FOR EACH STATEMENT
EXECUTE FUNCTION interdire_modification();
