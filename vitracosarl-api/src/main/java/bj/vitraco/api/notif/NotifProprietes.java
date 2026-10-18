package bj.vitraco.api.notif;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("vitraco.notifications")
public record NotifProprietes(
		/** Racine des écrans de l'application, devant la route d'une notification (hash routing). */
		@DefaultValue("http://localhost:4200/#") String appUrl) {
}
