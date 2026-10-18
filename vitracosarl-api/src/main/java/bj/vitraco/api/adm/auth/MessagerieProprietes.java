package bj.vitraco.api.adm.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("vitraco.mail")
public record MessagerieProprietes(
		@DefaultValue("") String from,
		@DefaultValue("VITRACO Moto") String fromName) {
}
