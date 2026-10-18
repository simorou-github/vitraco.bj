-- TRF 3.3 : transferts internes à trois temps (EF-TRF-01 à EF-TRF-10) et premières anomalies (A12 : transfert non réceptionné).

-- Les anomalies s'ouvrent depuis les modules métier ; leur traitement (justification, régularisation, clôture) viendra avec le module ANO.
CREATE TABLE anomalies
(
    id           BIGSERIAL PRIMARY KEY,
    numero       VARCHAR(40)  NOT NULL,
    code         VARCHAR(5)   NOT NULL,
    statut       VARCHAR(15)  NOT NULL DEFAULT 'OUVERTE',
    objet_type   VARCHAR(40)  NOT NULL,
    objet_id     BIGINT       NOT NULL,
    titre        VARCHAR(255) NOT NULL,
    description  VARCHAR(2000),
    -- Exposition financière de l'écart, quand elle se chiffre (coût des châssis manquants, par exemple).
    montant_fcfa BIGINT,
    ouverte_le   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ouverte_par  BIGINT REFERENCES utilisateurs (id),
    CONSTRAINT ck_anomalies_statut CHECK (statut IN ('OUVERTE', 'EN_COURS', 'JUSTIFIEE', 'REGULARISEE', 'CLOTUREE'))
);

CREATE UNIQUE INDEX ux_anomalies_numero ON anomalies (numero);
-- Une seule anomalie vivante par code et par objet : un nouvel écart sur le même transfert met la première à jour.
CREATE UNIQUE INDEX ux_anomalies_vivante ON anomalies (code, objet_type, objet_id) WHERE statut IN ('OUVERTE', 'EN_COURS');

CREATE TABLE transferts
(
    id               BIGSERIAL PRIMARY KEY,
    numero           VARCHAR(40)  NOT NULL,
    source_id        BIGINT       NOT NULL REFERENCES emplacements (id),
    destination_id   BIGINT       NOT NULL REFERENCES emplacements (id),
    -- INITIE : créé, aucun mouvement. EXPEDIE : les châssis sont en transit interne. RECEPTIONNE : tout est arrivé. ANNULE : avant expédition seulement.
    statut           VARCHAR(12)  NOT NULL DEFAULT 'INITIE',
    observation      VARCHAR(1000),
    -- Après expédition, seul un transfert retour corrige (EF-TRF-07).
    retour_de_id     BIGINT REFERENCES transferts (id),
    cree_par         BIGINT       NOT NULL REFERENCES utilisateurs (id),
    cree_le          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expedie_par      BIGINT REFERENCES utilisateurs (id),
    expedie_le       TIMESTAMPTZ,
    receptionne_le   TIMESTAMPTZ,
    annule_par       BIGINT REFERENCES utilisateurs (id),
    annule_le        TIMESTAMPTZ,
    motif_annulation VARCHAR(500),
    -- Réception clôturée avec des châssis manquants : l'anomalie A12 est ouverte.
    ecart_signale_le TIMESTAMPTZ,
    alerte_retard_le TIMESTAMPTZ,
    CONSTRAINT ck_transferts_statut CHECK (statut IN ('INITIE', 'EXPEDIE', 'RECEPTIONNE', 'ANNULE')),
    CONSTRAINT ck_transferts_lieux CHECK (source_id <> destination_id),
    CONSTRAINT ck_transferts_expedition CHECK ((statut IN ('EXPEDIE', 'RECEPTIONNE')) = (expedie_par IS NOT NULL AND expedie_le IS NOT NULL)),
    CONSTRAINT ck_transferts_reception CHECK ((statut = 'RECEPTIONNE') = (receptionne_le IS NOT NULL)),
    CONSTRAINT ck_transferts_annulation CHECK ((statut = 'ANNULE') = (annule_par IS NOT NULL AND annule_le IS NOT NULL AND motif_annulation IS NOT NULL))
);

CREATE UNIQUE INDEX ux_transferts_numero ON transferts (numero);
CREATE INDEX idx_transferts_source ON transferts (source_id, statut);
CREATE INDEX idx_transferts_destination ON transferts (destination_id, statut);

CREATE TABLE lignes_transfert
(
    id            BIGSERIAL PRIMARY KEY,
    transfert_id  BIGINT      NOT NULL REFERENCES transferts (id),
    chassis_id    BIGINT      NOT NULL REFERENCES chassis (id),
    -- Statut du châssis avant l'expédition : il le retrouve à l'arrivée (disponible, ou réservé pour un retrait sur place).
    statut_origine VARCHAR(30) NOT NULL,
    recu_le       TIMESTAMPTZ,
    recu_par      BIGINT REFERENCES utilisateurs (id),
    CONSTRAINT ux_lignes_transfert UNIQUE (transfert_id, chassis_id),
    CONSTRAINT ck_lignes_transfert_reception CHECK ((recu_le IS NULL) = (recu_par IS NULL))
);

CREATE INDEX idx_lignes_transfert_chassis ON lignes_transfert (chassis_id);
