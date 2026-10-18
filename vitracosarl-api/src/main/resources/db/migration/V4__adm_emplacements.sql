-- ADM 1.3 : emplacements (EF-ADM-01, EF-ADM-08, EF-ADM-09) et périmètre des utilisateurs (EF-ADM-13).

CREATE TABLE emplacements
(
    id                      BIGSERIAL PRIMARY KEY,
    code                    VARCHAR(20)  NOT NULL,
    nom                     VARCHAR(255) NOT NULL,
    type                    VARCHAR(20)  NOT NULL,
    mode_boutique           VARCHAR(1),
    magasin_rattachement_id BIGINT REFERENCES emplacements (id),
    -- Dépôt chez un revendeur : la fiche client arrive avec le module CLI (la clé étrangère s'y ajoutera).
    revendeur_id            BIGINT,
    adresse                 VARCHAR(255),
    responsable_nom         VARCHAR(255),
    email                   VARCHAR(255),
    telephone               VARCHAR(30),
    actif                   BOOLEAN      NOT NULL DEFAULT TRUE,
    -- Emplacement créé et géré par le système (par exemple « transit interne ») : ni modifiable ni désactivable.
    systeme                 BOOLEAN      NOT NULL DEFAULT FALSE,
    version                 BIGINT       NOT NULL DEFAULT 0,
    cree_le                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    modifie_le              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    cree_par                BIGINT REFERENCES utilisateurs (id),
    modifie_par             BIGINT REFERENCES utilisateurs (id),
    CONSTRAINT ck_emplacements_type CHECK (type IN ('MAGASIN', 'BOUTIQUE', 'DEPOT_REVENDEUR', 'TRANSIT')),
    CONSTRAINT ck_emplacements_mode CHECK (mode_boutique IN ('A', 'B')),
    -- Seule une boutique a un mode ; une boutique en a toujours un.
    CONSTRAINT ck_emplacements_mode_boutique CHECK ((type = 'BOUTIQUE') = (mode_boutique IS NOT NULL)),
    -- Une boutique en mode B vend sur le stock de son magasin de rattachement ; en mode A elle n'en a pas.
    CONSTRAINT ck_emplacements_rattachement CHECK ((mode_boutique = 'B') = (magasin_rattachement_id IS NOT NULL))
);

CREATE UNIQUE INDEX ux_emplacements_code ON emplacements (upper(code));

-- Emplacements autorisés d'un utilisateur : il ne voit que les données de ces emplacements (ADM-13).
CREATE TABLE emplacement_utilisateur
(
    utilisateur_id BIGINT NOT NULL REFERENCES utilisateurs (id) ON DELETE CASCADE,
    emplacement_id BIGINT NOT NULL REFERENCES emplacements (id),
    PRIMARY KEY (utilisateur_id, emplacement_id)
);

-- Rôle à périmètre consolidé : voit tous les emplacements (la Direction, STK-10).
ALTER TABLE roles
    ADD COLUMN perimetre_global BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE roles
SET perimetre_global = TRUE
WHERE code IN ('admin', 'direction');
