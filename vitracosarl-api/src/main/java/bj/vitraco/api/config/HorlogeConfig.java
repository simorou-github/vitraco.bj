package bj.vitraco.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/** L'heure fait foi côté serveur, au fuseau du Bénin (ENF-19, ENF-21). */
@Configuration
class HorlogeConfig {

	static final ZoneId FUSEAU = ZoneId.of("Africa/Porto-Novo");

	@Bean
	Clock horloge() {
		return Clock.system(FUSEAU);
	}

}
