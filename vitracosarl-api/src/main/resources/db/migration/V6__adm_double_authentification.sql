-- ADM 1.5 : double authentification par application (TOTP) et codes de secours (EF-ADM-10, US-002).

-- Le second facteur est exigé pour Administrateur, Direction et Comptabilité (SRS 7.1), réglable par rôle.
ALTER TABLE roles
    ADD COLUMN double_authentification_requise BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE roles
SET double_authentification_requise = TRUE
WHERE code IN ('admin', 'direction', 'comptabilite_caisse');

-- Le secret TOTP doit pouvoir être relu pour vérifier un code : il est chiffré (AES-GCM), jamais en clair.
-- Tant que a2f_active_le est vide, le secret est en cours d'enrôlement et ne protège pas encore le compte.
-- a2f_dernier_pas mémorise le dernier pas de 30 s accepté : un même code ne sert qu'une fois.
ALTER TABLE utilisateurs
    ADD COLUMN a2f_secret_chiffre VARCHAR(255),
    ADD COLUMN a2f_active_le      TIMESTAMPTZ,
    ADD COLUMN a2f_dernier_pas    BIGINT;

-- Codes de secours à usage unique, conservés sous forme d'empreinte SHA-256 comme les jetons de compte.
CREATE TABLE codes_secours
(
    id             BIGSERIAL PRIMARY KEY,
    utilisateur_id BIGINT      NOT NULL REFERENCES utilisateurs (id) ON DELETE CASCADE,
    empreinte      VARCHAR(64) NOT NULL,
    utilise_le     TIMESTAMPTZ,
    cree_le        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_codes_secours UNIQUE (utilisateur_id, empreinte)
);
