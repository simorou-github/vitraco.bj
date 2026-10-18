-- Notifications dans l'application (EF-NOT-01, EF-NOT-03). Le courriel qui les double n'est pas conservé ici.

CREATE TABLE notifications
(
    id              BIGSERIAL PRIMARY KEY,
    destinataire_id BIGINT       NOT NULL REFERENCES utilisateurs (id),
    type            VARCHAR(60)  NOT NULL,
    titre           VARCHAR(255) NOT NULL,
    message         TEXT         NOT NULL,
    -- Route de l'écran qui traite la notification (EF-NOT-03).
    lien            VARCHAR(255),
    cree_le         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    lue_le          TIMESTAMPTZ
);

CREATE INDEX idx_notifications_destinataire ON notifications (destinataire_id, cree_le DESC);
CREATE INDEX idx_notifications_non_lues ON notifications (destinataire_id) WHERE lue_le IS NULL;
