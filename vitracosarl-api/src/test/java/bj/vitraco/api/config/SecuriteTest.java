package bj.vitraco.api.config;

import bj.vitraco.api.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SecuriteTest {

	@Autowired
	MockMvc mvc;

	@Test
	void lEtatDeSanteEstPublicEtVerifieLaBase() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	void toutLeResteExigeUneAuthentification() throws Exception {
		mvc.perform(get("/api/v1/quelque-chose")).andExpect(status().isUnauthorized());
	}

	@Test
	void uneEcritureSansJetonCsrfEstRefusee() throws Exception {
		mvc.perform(post("/api/v1/quelque-chose")).andExpect(status().isForbidden());
	}

	@Test
	void leFrontAutoriseEstAccepteAvecSesCookiesEtUneOrigineInconnueRefusee() throws Exception {
		mvc.perform(options("/api/v1/auth/login")
						.header("Origin", "http://localhost:4200")
						.header("Access-Control-Request-Method", "POST")
						.header("Access-Control-Request-Headers", "content-type,x-xsrf-token"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"))
				.andExpect(header().string("Access-Control-Allow-Credentials", "true"));

		mvc.perform(options("/api/v1/auth/login")
						.header("Origin", "http://site-inconnu.example")
						.header("Access-Control-Request-Method", "POST"))
				.andExpect(status().isForbidden());
	}

}
