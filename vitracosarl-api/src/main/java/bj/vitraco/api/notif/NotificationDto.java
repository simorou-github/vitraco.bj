package bj.vitraco.api.notif;

import java.time.Instant;

public record NotificationDto(Long id, String type, String titre, String message, String lien, boolean lue, Instant creeLe) {
}
