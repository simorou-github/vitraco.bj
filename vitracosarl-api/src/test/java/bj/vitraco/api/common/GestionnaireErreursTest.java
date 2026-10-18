package bj.vitraco.api.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GestionnaireErreursTest {

	record Demande(@NotBlank(message = "Le nom est requis.") String nom,
				   @NotBlank(message = "Le nom de famille est requis.") String nomDeFamille) {
	}

	@RestController
	static class ControleurDeTest {

		@GetMapping("/metier")
		void metier() {
			throw new ErreurMetierException("CHASSIS_DEJA_RESERVE", "Ce châssis est déjà réservé.");
		}

		@GetMapping("/metier-404")
		void introuvable() {
			throw new ErreurMetierException("INTROUVABLE", "Introuvable.", HttpStatus.NOT_FOUND);
		}

		@GetMapping("/panne")
		void panne() {
			throw new IllegalStateException("détail technique à ne pas divulguer");
		}

		@PostMapping("/valider")
		void valider(@Valid @RequestBody Demande demande) {
		}

	}

	MockMvc mvc;

	@BeforeEach
	void preparer() {
		mvc = MockMvcBuilders.standaloneSetup(new ControleurDeTest()).setControllerAdvice(new GestionnaireErreurs()).build();
	}

	@Test
	void uneErreurMetierRenvoieSonCodeStableEtSonMessage() throws Exception {
		mvc.perform(get("/metier"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("CHASSIS_DEJA_RESERVE"))
				.andExpect(jsonPath("$.message").value("Ce châssis est déjà réservé."))
				.andExpect(jsonPath("$.errors").doesNotExist());
	}

	@Test
	void lEtatHttpDUneErreurMetierPeutEtreChoisi() throws Exception {
		mvc.perform(get("/metier-404")).andExpect(status().isNotFound());
	}

	@Test
	void unChampInvalideListeSesErreursParChamp() throws Exception {
		mvc.perform(post("/valider").contentType(MediaType.APPLICATION_JSON).content("{\"nom\":\"\"}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors.nom[0]").value("Le nom est requis."))
				// Les clés suivent le JSON de l'API (snake_case), pas le nom du champ Java.
				.andExpect(jsonPath("$.errors.nom_de_famille[0]").value("Le nom de famille est requis."));
	}

	@Test
	void uneErreurInattendueNeDivulguePasLeDetailTechnique() throws Exception {
		mvc.perform(get("/panne"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("ERREUR_INTERNE"))
				.andExpect(jsonPath("$.message").value("Une erreur interne est survenue."));
	}

}
