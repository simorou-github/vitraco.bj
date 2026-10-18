-- REC 3.1 : réceptions physiques par scan de châssis (EF-REC-01 à EF-REC-08, RG-06, RG-07, US-024 à US-027).
-- Un brouillon n'a aucun effet sur le stock (EF-REC-05) : les motos ne changent d'état qu'à la validation.

CREATE TABLE receptions
(
    id             BIGSERIAL PRIMARY KEY,
    numero         VARCHAR(40)  NOT NULL,
    arrivage_id    BIGINT       NOT NULL REFERENCES arrivages (id),
    -- Magasin où entrent les motos reçues.
    emplacement_id BIGINT       NOT NULL REFERENCES emplacements (id),
    statut         VARCHAR(10)  NOT NULL DEFAULT 'BROUILLON',
    observation    VARCHAR(1000),
    -- Rapprochement attendu / reçu / écart par modèle et châssis manquants, figés à la validation pour que le PV reste stable.
    resume         JSONB,
    version        BIGINT       NOT NULL DEFAULT 0,
    cree_le        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    modifie_le     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    cree_par       BIGINT       NOT NULL REFERENCES utilisateurs (id),
    modifie_par    BIGINT REFERENCES utilisateurs (id),
    valide_par     BIGINT REFERENCES utilisateurs (id),
    valide_le      TIMESTAMPTZ,
    CONSTRAINT ck_receptions_statut CHECK (statut IN ('BROUILLON', 'VALIDEE')),
    CONSTRAINT ck_receptions_validation CHECK ((statut = 'VALIDEE') = (valide_par IS NOT NULL AND valide_le IS NOT NULL AND resume IS NOT NULL))
);

CREATE UNIQUE INDEX ux_receptions_numero ON receptions (numero);
-- Une seule réception ouverte à la fois par arrivage : deux brouillons scanneraient les mêmes motos.
CREATE UNIQUE INDEX ux_receptions_brouillon_arrivage ON receptions (arrivage_id) WHERE statut = 'BROUILLON';
CREATE INDEX idx_receptions_emplacement ON receptions (emplacement_id);

CREATE TABLE lignes_reception
(
    id           BIGSERIAL PRIMARY KEY,
    reception_id BIGINT       NOT NULL REFERENCES receptions (id),
    numero       VARCHAR(40)  NOT NULL,
    modele_id    BIGINT       NOT NULL REFERENCES modeles (id),
    -- Renseigné pour une moto annoncée par le colisage ; à la validation pour les autres, une fois le châssis créé.
    chassis_id   BIGINT REFERENCES chassis (id),
    -- ANNONCE : listée par le colisage. NOUVEAU : arrivage sans colisage. INATTENDU : non annoncée, acceptée avec justification (EF-REC-06).
    nature       VARCHAR(10)  NOT NULL,
    endommage    BOOLEAN      NOT NULL DEFAULT FALSE,
    motif        VARCHAR(500),
    cree_par     BIGINT REFERENCES utilisateurs (id),
    cree_le      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_lignes_reception_nature CHECK (nature IN ('ANNONCE', 'NOUVEAU', 'INATTENDU')),
    CONSTRAINT ck_lignes_reception_motif CHECK ((NOT endommage AND nature <> 'INATTENDU') OR motif IS NOT NULL),
    CONSTRAINT ux_lignes_reception_numero UNIQUE (reception_id, numero)
);

CREATE INDEX idx_lignes_reception ON lignes_reception (reception_id);
