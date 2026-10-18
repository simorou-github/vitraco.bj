-- STK 3.4 : import du stock initial par châssis (US-006) et rapprochement du stock par pointage (reprise de données, SRS 14.2).

CREATE TABLE imports_stock
(
    id               BIGSERIAL PRIMARY KEY,
    nom_fichier      VARCHAR(255) NOT NULL,
    nb_lignes        INTEGER      NOT NULL,
    nb_importees     INTEGER      NOT NULL,
    nb_rejetees      INTEGER      NOT NULL,
    -- Répartition des motos importées par emplacement et valeur au coût de revient, pour le rapport.
    repartition      JSONB        NOT NULL DEFAULT '[]',
    rejets           JSONB        NOT NULL DEFAULT '[]',
    cree_le          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    cree_par         BIGINT REFERENCES utilisateurs (id)
);

-- Un pointage compare ce que le système affiche à ce qui est physiquement dans un emplacement. Il ne corrige rien :
-- un écart se régularise par un ajustement validé (module AJU). On garde le rapport comme preuve de la reprise.
CREATE TABLE rapprochements_stock
(
    id             BIGSERIAL PRIMARY KEY,
    emplacement_id BIGINT      NOT NULL REFERENCES emplacements (id),
    nb_attendus    INTEGER     NOT NULL,
    nb_pointes     INTEGER     NOT NULL,
    nb_conformes   INTEGER     NOT NULL,
    manquants      JSONB       NOT NULL DEFAULT '[]',
    inattendus     JSONB       NOT NULL DEFAULT '[]',
    cree_le        TIMESTAMPTZ NOT NULL DEFAULT now(),
    cree_par       BIGINT REFERENCES utilisateurs (id)
);

CREATE INDEX idx_rapprochements_emplacement ON rapprochements_stock (emplacement_id, cree_le DESC);
