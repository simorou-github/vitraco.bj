-- ADM : utilisateurs, rôles, permissions, jetons de compte (EF-ADM-02, EF-ADM-03, EF-ADM-10).

CREATE TABLE permissions
(
    id      BIGSERIAL PRIMARY KEY,
    code    VARCHAR(100) NOT NULL UNIQUE,
    libelle VARCHAR(255) NOT NULL,
    module  VARCHAR(50)  NOT NULL,
    action  VARCHAR(30)  NOT NULL
);

CREATE TABLE roles
(
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(60)  NOT NULL UNIQUE,
    libelle     VARCHAR(255) NOT NULL,
    description TEXT,
    -- Rôle livré par défaut : ses droits s'ajustent, mais il ne se supprime ni ne se renomme.
    systeme     BOOLEAN      NOT NULL DEFAULT FALSE,
    cree_le     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    modifie_le  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    cree_par    BIGINT
);

CREATE TABLE role_permission
(
    role_id       BIGINT NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission_id BIGINT NOT NULL REFERENCES permissions (id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE utilisateurs
(
    id                   BIGSERIAL PRIMARY KEY,
    nom                  VARCHAR(255) NOT NULL,
    prenom               VARCHAR(255),
    email                VARCHAR(255) NOT NULL,
    telephone            VARCHAR(30),
    adresse              VARCHAR(255),
    mot_de_passe_hash    VARCHAR(255),
    statut               VARCHAR(30)  NOT NULL,
    tentatives_echec     INT          NOT NULL DEFAULT 0,
    verrouille_jusqua    TIMESTAMPTZ,
    derniere_connexion   TIMESTAMPTZ,
    version              BIGINT       NOT NULL DEFAULT 0,
    cree_le              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    modifie_le           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    cree_par             BIGINT REFERENCES utilisateurs (id),
    modifie_par          BIGINT REFERENCES utilisateurs (id),
    CONSTRAINT ck_utilisateurs_statut CHECK (statut IN ('EN_ATTENTE_ACTIVATION', 'ACTIF', 'SUSPENDU'))
);

-- Un e-mail est unique sans tenir compte de la casse (RG : un compte par personne).
CREATE UNIQUE INDEX ux_utilisateurs_email ON utilisateurs (lower(email));

ALTER TABLE roles
    ADD CONSTRAINT fk_roles_cree_par FOREIGN KEY (cree_par) REFERENCES utilisateurs (id);

CREATE TABLE utilisateur_role
(
    utilisateur_id BIGINT NOT NULL REFERENCES utilisateurs (id) ON DELETE CASCADE,
    role_id        BIGINT NOT NULL REFERENCES roles (id),
    PRIMARY KEY (utilisateur_id, role_id)
);

-- Lien d'activation (24 h) ou de réinitialisation du mot de passe. Seule l'empreinte
-- du jeton est conservée : une fuite de la base ne donne accès à aucun compte.
CREATE TABLE jetons_compte
(
    id             BIGSERIAL PRIMARY KEY,
    utilisateur_id BIGINT      NOT NULL REFERENCES utilisateurs (id) ON DELETE CASCADE,
    type           VARCHAR(30) NOT NULL,
    empreinte      VARCHAR(64) NOT NULL UNIQUE,
    expire_le      TIMESTAMPTZ NOT NULL,
    utilise_le     TIMESTAMPTZ,
    cree_le        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_jetons_type CHECK (type IN ('ACTIVATION', 'REINITIALISATION'))
);

CREATE INDEX idx_jetons_utilisateur ON jetons_compte (utilisateur_id);
