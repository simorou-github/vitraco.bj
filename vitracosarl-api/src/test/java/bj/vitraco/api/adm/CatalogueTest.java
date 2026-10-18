package bj.vitraco.api.adm;

import bj.vitraco.api.TestcontainersConfiguration;
import bj.vitraco.api.cat.PrixService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CatalogueTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	PrixService prix;

	private ComptesDeTest comptes() {
		return new ComptesDeTest(mvc, jdbc);
	}

	private static String code() {
		return "CB" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
	}

	private static String corpsModele(String code, long vente, long minimum) {
		return """
				{"marque":"Honda","modele":"CG 125","version":"2026","code_article":"%s","description":"Moto de ville",
				 "groupe_taxation":"b","format_chassis":"^LWB[A-Z0-9]{14}$","prix_vente":%d,"prix_minimum":%d}""".formatted(code, vente, minimum);
	}

	private Long idModele(String code) {
		return jdbc.sql("SELECT id FROM modeles WHERE code_article = :c").param("c", code).query(Long.class).single();
	}

	private ClientHttp creerEtConnecter(boolean achat, boolean valider) throws Exception {
		ComptesDeTest c = comptes();
		java.util.List<String> droits = new java.util.ArrayList<>(java.util.List.of("catalogue.voir", "catalogue.creer", "catalogue.modifier"));
		if (achat) {
			droits.add("prix_achat.voir");
		}
		if (valider) {
			droits.add("catalogue.valider");
		}
		return c.connecter(c.utilisateurActif(c.role(false, droits.toArray(new String[0]))));
	}

	@Test
	void unModeleSeCreeAvecSesPrixInitiauxEtSonCodeArticleEstUnique() throws Exception {
		ClientHttp admin = comptes().admin();
		String code = code();
		admin.post("/api/v1/modeles", corpsModele(code.toLowerCase(), 1_200_000, 1_100_000)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.code_article").value(code)).andExpect(jsonPath("$.data.groupe_taxation").value("B"))
				.andExpect(jsonPath("$.data.prix_vente").value(1_200_000)).andExpect(jsonPath("$.data.prix_minimum").value(1_100_000))
				.andExpect(jsonPath("$.data.actif").value(true));

		admin.post("/api/v1/modeles", corpsModele(code, 1_200_000, 1_100_000)).andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors.code_article[0]").exists());
		// Le prix minimum ne dépasse pas le prix de vente (US-010).
		admin.post("/api/v1/modeles", corpsModele(code(), 1_000_000, 1_100_000)).andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors.prix_minimum[0]").exists());
		admin.post("/api/v1/modeles", corpsModele(code(), 1_000_000, 900_000).replace("\"b\"", "\"Z\""))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.groupe_taxation").exists());
		admin.post("/api/v1/modeles", corpsModele(code(), 1_000_000, 900_000).replace("^LWB[A-Z0-9]{14}$", "(["))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.format_chassis[0]").exists());
		admin.post("/api/v1/modeles", corpsModele("a b", 1, 1)).andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors.code_article").exists());
		admin.post("/api/v1/modeles", "{\"marque\":\"Honda\",\"modele\":\"X\",\"code_article\":\"%s\"}".formatted(code()))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.prix_vente").exists());

		assertThat(jdbc.sql("SELECT count(*) FROM journal_audit WHERE action = 'modele.cree' AND objet_id = :i")
				.param("i", String.valueOf(idModele(code))).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void leCatalogueSeListeSeFiltreEtSeLitSansDroitDEcriture() throws Exception {
		ClientHttp admin = comptes().admin();
		String code = code();
		admin.post("/api/v1/modeles", corpsModele(code, 800_000, 750_000).replace("Honda", "Sanya")).andExpect(status().isCreated());

		admin.get("/api/v1/modeles?search=" + code.toLowerCase()).andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(1)))
				.andExpect(jsonPath("$.data[0].prix_vente").value(800_000)).andExpect(jsonPath("$.meta.total").value(1));
		admin.get("/api/v1/modeles?marque=sanya&search=" + code).andExpect(jsonPath("$.data", hasSize(1)));
		admin.get("/api/v1/modeles?marque=Loncin&search=" + code).andExpect(jsonPath("$.data", hasSize(0)));
		admin.get("/api/v1/modeles/marques").andExpect(status().isOk()).andExpect(jsonPath("$.data").value(hasItem("Sanya")));

		// Une boutique consulte le catalogue mais ne le modifie pas.
		ComptesDeTest c = comptes();
		ClientHttp boutique = c.connecter(c.utilisateurActif("boutique"));
		boutique.get("/api/v1/modeles?search=" + code).andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(1)));
		boutique.post("/api/v1/modeles", corpsModele(code(), 1, 1)).andExpect(status().isForbidden());
		boutique.put("/api/v1/modeles/" + idModele(code) + "/prix", "{\"prix_vente\":1}").andExpect(status().isForbidden());
		new ClientHttp(mvc).get("/api/v1/modeles").andExpect(status().isUnauthorized());
	}

	@Test
	void lePrixDAchatNEstJamaisEnvoyeSansLaPermission() throws Exception {
		ClientHttp acheteur = creerEtConnecter(true, false);
		ClientHttp admin = comptes().admin();
		String code = code();
		acheteur.post("/api/v1/modeles", corpsModele(code, 1_500_000, 1_400_000).replace("\"prix_minimum\"", "\"prix_achat_reference\":1000000,\"prix_minimum\"")
				.replace("1400000", "1400000")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.prix_achat_reference").value(1_000_000)).andExpect(jsonPath("$.data.prix_achat_visible").value(true));
		Long id = idModele(code);

		// L'administrateur a le droit d'écrire au catalogue, pas de voir les prix d'achat (SRS 9.1).
		admin.get("/api/v1/modeles/" + id).andExpect(status().isOk()).andExpect(jsonPath("$.data.prix_achat_reference").doesNotExist())
				.andExpect(jsonPath("$.data.prix_achat_visible").value(false)).andExpect(jsonPath("$.data.prix_vente").value(1_500_000));
		admin.get("/api/v1/modeles?search=" + code).andExpect(jsonPath("$.data[0].prix_achat_reference").doesNotExist());
		admin.get("/api/v1/modeles/" + id + "/prix").andExpect(jsonPath("$.data[*].type").value(org.hamcrest.Matchers.not(hasItem("ACHAT_REFERENCE"))));
		acheteur.get("/api/v1/modeles/" + id + "/prix").andExpect(jsonPath("$.data[*].type").value(hasItem("ACHAT_REFERENCE")));

		admin.put("/api/v1/modeles/" + id + "/prix", "{\"prix_achat_reference\":900000}").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCES_REFUSE"));
		admin.post("/api/v1/modeles", corpsModele(code(), 1_500_000, 1_400_000).replace("\"prix_minimum\"", "\"prix_achat_reference\":1,\"prix_minimum\"").replace("1400000", "1400000"))
				.andExpect(status().isForbidden());
	}

	@Test
	void unChangementDePrixAvecDateDEffetNeTouchePasLePrixEnVigueurEtGardeSonHistorique() throws Exception {
		ClientHttp admin = comptes().admin();
		String code = code();
		admin.post("/api/v1/modeles", corpsModele(code, 1_000_000, 900_000)).andExpect(status().isCreated());
		Long id = idModele(code);
		LocalDate dansDix = LocalDate.now().plusDays(10);

		admin.put("/api/v1/modeles/" + id + "/prix", "{\"prix_vente\":1100000,\"date_effet\":\"%s\",\"motif\":\"Hausse annoncée\"}".formatted(dansDix))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.prix_vente").value(1_000_000))
				.andExpect(jsonPath("$.data.prix_programmes", hasSize(1))).andExpect(jsonPath("$.data.prix_programmes[0].valeur").value(1_100_000))
				.andExpect(jsonPath("$.data.prix_programmes[0].date_effet").value(dansDix.toString()));

		// Les modules de vente lisent le prix à la date voulue : une commande validée aujourd'hui garde son prix.
		assertThat(prix.prixVenteAu(id, LocalDate.now())).contains(1_000_000L);
		assertThat(prix.prixVenteAu(id, dansDix.minusDays(1))).contains(1_000_000L);
		assertThat(prix.prixVenteAu(id, dansDix)).contains(1_100_000L);

		admin.put("/api/v1/modeles/" + id + "/prix", "{\"prix_vente\":1050000}").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.prix_vente").value(1_050_000));
		admin.get("/api/v1/modeles/" + id + "/prix").andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(4)))
				.andExpect(jsonPath("$.data[0].motif").value("Hausse annoncée")).andExpect(jsonPath("$.data[0].cree_par_libelle").value("Administrateur"));
		assertThat(jdbc.sql("SELECT count(*) FROM journal_audit WHERE action = 'modele.prix_modifie' AND objet_id = :i")
				.param("i", String.valueOf(id)).query(Long.class).single()).isEqualTo(2);
	}

	@Test
	void lesChangementsInvalidesSontRefuses() throws Exception {
		ClientHttp admin = comptes().admin();
		String code = code();
		admin.post("/api/v1/modeles", corpsModele(code, 1_000_000, 900_000)).andExpect(status().isCreated());
		Long id = idModele(code);

		admin.put("/api/v1/modeles/" + id + "/prix", "{}").andExpect(status().isUnprocessableEntity());
		admin.put("/api/v1/modeles/" + id + "/prix", "{\"prix_vente\":1000000}").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("PRIX_INCHANGE"));
		admin.put("/api/v1/modeles/" + id + "/prix", "{\"prix_vente\":1200000,\"date_effet\":\"2020-01-01\"}").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors.date_effet[0]").exists());
		admin.put("/api/v1/modeles/" + id + "/prix", "{\"prix_vente\":-5}").andExpect(status().isUnprocessableEntity());
		// Un prix de vente sous le minimum en vigueur est refusé tant que le minimum n'est pas baissé avec lui.
		admin.put("/api/v1/modeles/" + id + "/prix", "{\"prix_vente\":850000}").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors.prix_minimum[0]").exists());
		admin.put("/api/v1/modeles/999999/prix", "{\"prix_vente\":1}").andExpect(status().isNotFound());
	}

	@Test
	void unPrixProgrammeNeDoitJamaisRendreLeMinimumSuperieurAuPrixDeVente() throws Exception {
		ClientHttp admin = comptes().admin();
		String code = code();
		admin.post("/api/v1/modeles", corpsModele(code, 1_000_000, 900_000)).andExpect(status().isCreated());
		Long id = idModele(code);
		LocalDate dansVingt = LocalDate.now().plusDays(20);
		LocalDate dansCinq = LocalDate.now().plusDays(5);

		admin.put("/api/v1/modeles/" + id + "/prix", "{\"prix_vente\":950000,\"date_effet\":\"%s\"}".formatted(dansVingt)).andExpect(status().isOk());
		// À J+5 le minimum de 980 000 passe, mais à J+20 le prix de vente programmé (950 000) serait en dessous.
		admin.put("/api/v1/modeles/" + id + "/prix", "{\"prix_minimum\":980000,\"date_effet\":\"%s\"}".formatted(dansCinq))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.prix_minimum[0]").value(org.hamcrest.Matchers.containsString(dansVingt.toString())));
		// En ajustant les deux, c'est accepté.
		admin.put("/api/v1/modeles/" + id + "/prix", "{\"prix_minimum\":940000,\"date_effet\":\"%s\"}".formatted(dansCinq)).andExpect(status().isOk());
	}

	@Test
	void baisserLePrixMinimumExigeLaValidationDeLaDirection() throws Exception {
		ClientHttp admin = comptes().admin();
		ClientHttp commercial = creerEtConnecter(false, false);
		String code = code();
		admin.post("/api/v1/modeles", corpsModele(code, 1_000_000, 900_000)).andExpect(status().isCreated());
		Long id = idModele(code);

		// Sans le droit de valider, abaisser le plancher est refusé, même avec le droit de modifier.
		commercial.put("/api/v1/modeles/" + id + "/prix", "{\"prix_vente\":800000,\"prix_minimum\":700000}").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("VALIDATION_DIRECTION_REQUISE"));
		assertThat(prix.aujourdhui(id).minimum()).isEqualTo(900_000);

		ClientHttp direction = creerEtConnecter(false, true);
		direction.put("/api/v1/modeles/" + id + "/prix", "{\"prix_vente\":800000,\"prix_minimum\":700000,\"motif\":\"Promotion de fin de série\"}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.prix_vente").value(800_000)).andExpect(jsonPath("$.data.prix_minimum").value(700_000));
		// Relever le plancher ne demande aucune validation.
		admin.put("/api/v1/modeles/" + id + "/prix", "{\"prix_minimum\":750000}").andExpect(status().isOk());
	}

	@Test
	void lHistoriqueDesPrixEstEnAjoutSeulMemeSansPasserParLApi() throws Exception {
		ClientHttp admin = comptes().admin();
		String code = code();
		admin.post("/api/v1/modeles", corpsModele(code, 1_000_000, 900_000)).andExpect(status().isCreated());
		Long id = idModele(code);

		assertThatThrownBy(() -> jdbc.sql("UPDATE prix_historique SET valeur = 1 WHERE modele_id = :m").param("m", id).update())
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("DELETE FROM prix_historique WHERE modele_id = :m").param("m", id).update())
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(jdbc.sql("SELECT count(*) FROM prix_historique WHERE modele_id = :m").param("m", id).query(Long.class).single()).isEqualTo(2);
	}

	@Test
	void uneFicheSeModifieSansToucherAuCodeNiAuxPrixEtUnModeleSeDesactive() throws Exception {
		ClientHttp admin = comptes().admin();
		String code = code();
		admin.post("/api/v1/modeles", corpsModele(code, 1_000_000, 900_000)).andExpect(status().isCreated());
		Long id = idModele(code);

		admin.put("/api/v1/modeles/" + id, "{\"marque\":\"Honda\",\"modele\":\"CG 150\",\"version\":\"2027\",\"groupe_taxation\":\"A\",\"format_chassis\":\"^[A-Z0-9]{17}$\"}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.modele").value("CG 150")).andExpect(jsonPath("$.data.code_article").value(code))
				.andExpect(jsonPath("$.data.prix_vente").value(1_000_000)).andExpect(jsonPath("$.data.description").doesNotExist());
		admin.put("/api/v1/modeles/" + id, "{\"marque\":\"Honda\",\"modele\":\"X\",\"format_chassis\":\"(\"}").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors.format_chassis[0]").exists());

		admin.post("/api/v1/modeles/" + id + "/desactiver", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.data.actif").value(false));
		admin.post("/api/v1/modeles/" + id + "/desactiver", "{}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DEJA_DESACTIVE"));
		admin.get("/api/v1/modeles?actif=true&search=" + code).andExpect(jsonPath("$.data", hasSize(0)));
		admin.get("/api/v1/modeles?actif=false&search=" + code).andExpect(jsonPath("$.data", hasSize(1)));
		admin.post("/api/v1/modeles/" + id + "/reactiver", "{}").andExpect(status().isOk());
		admin.delete("/api/v1/modeles/" + id).andExpect(status().is4xxClientError());
		assertThat(jdbc.sql("SELECT count(*) FROM modeles WHERE id = :i").param("i", id).query(Long.class).single()).isEqualTo(1);
	}

}
