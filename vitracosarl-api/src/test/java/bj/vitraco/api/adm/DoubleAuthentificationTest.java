package bj.vitraco.api.adm;

import bj.vitraco.api.TestcontainersConfiguration;
import bj.vitraco.api.adm.auth.Totp;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class DoubleAuthentificationTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;

	private ComptesDeTest comptes() {
		return new ComptesDeTest(mvc, jdbc);
	}

	private static String code(String secret, long decalage) {
		return Totp.code(secret, Totp.pasCourant(Instant.now()) + decalage);
	}

	private static String corps(String code) {
		return "{\"code\":\"%s\"}".formatted(code);
	}

	/** Premier passage d'un utilisateur dont le rôle exige le second facteur : il le configure et se retrouve connecté. */
	private record Enrole(ClientHttp client, String secret, List<String> codesSecours) {
	}

	private Enrole enroler(String email) throws Exception {
		ClientHttp client = new ClientHttp(mvc);
		client.post("/api/v1/auth/login", ComptesDeTest.corpsConnexion(email))
				.andExpect(status().isAccepted()).andExpect(jsonPath("$.data.etape").value("CONFIGURATION"));
		// Mot de passe validé, mais aucun droit tant que le second facteur n'est pas en place.
		client.get("/api/v1/auth/me").andExpect(status().isUnauthorized());
		client.get("/api/v1/utilisateurs").andExpect(status().isUnauthorized());

		String secret = JsonPath.read(client.get("/api/v1/auth/2fa/configuration").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.otpauth_uri").value(org.hamcrest.Matchers.startsWith("otpauth://totp/")))
				.andReturn().getResponse().getContentAsString(), "$.data.secret");
		client.post("/api/v1/auth/2fa/activer", corps("000000")).andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("CODE_A2F_INVALIDE"));
		String reponse = client.post("/api/v1/auth/2fa/activer", corps(code(secret, 0))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.codes_secours", hasSize(10))).andExpect(jsonPath("$.data.utilisateur.a2f_active").value(true))
				.andReturn().getResponse().getContentAsString();
		client.get("/api/v1/auth/me").andExpect(status().isOk());
		return new Enrole(client, secret, JsonPath.read(reponse, "$.data.codes_secours"));
	}

	@Test
	void unRoleQuiExigeLeSecondFacteurLeFaitConfigurerALaPremiereConnexion() throws Exception {
		ComptesDeTest comptes = comptes();
		String email = comptes.utilisateurActif(comptes.role(true, "stock.voir"));

		Enrole enrole = enroler(email);

		// Le secret n'est jamais stocké en clair, et les codes de secours seulement sous forme d'empreinte.
		String stocke = jdbc.sql("SELECT a2f_secret_chiffre FROM utilisateurs WHERE email = :e").param("e", email).query(String.class).single();
		assertThat(stocke).isNotBlank().doesNotContain(enrole.secret());
		assertThat(jdbc.sql("SELECT count(*) FROM codes_secours WHERE utilisateur_id = :u AND empreinte = :c")
				.param("u", comptes.id(email)).param("c", enrole.codesSecours().get(0).replace("-", "")).query(Long.class).single()).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM journal_audit WHERE action = 'auth.a2f_activee' AND utilisateur_id = :u")
				.param("u", comptes.id(email)).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void ensuiteLaConnexionExigeUnCodeValideEtNeSeRejouePas() throws Exception {
		ComptesDeTest comptes = comptes();
		String email = comptes.utilisateurActif(comptes.role(true, "stock.voir"));
		Enrole enrole = enroler(email);

		ClientHttp client = new ClientHttp(mvc);
		client.post("/api/v1/auth/login", ComptesDeTest.corpsConnexion(email))
				.andExpect(status().isAccepted()).andExpect(jsonPath("$.data.etape").value("VERIFICATION"));
		client.get("/api/v1/auth/me").andExpect(status().isUnauthorized());
		client.post("/api/v1/auth/2fa/verifier", corps("123456")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("CODE_A2F_INVALIDE"));
		// Le code qui a servi à l'activation est déjà « consommé » : le rejouer échoue.
		client.post("/api/v1/auth/2fa/verifier", corps(code(enrole.secret(), 0))).andExpect(status().isUnauthorized());
		// Le pas suivant est accepté (tolérance d'horloge d'un pas).
		client.post("/api/v1/auth/2fa/verifier", corps(code(enrole.secret(), 1))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.email").value(email));
		client.get("/api/v1/auth/me").andExpect(status().isOk());
	}

	@Test
	void unCodeDeSecoursOuvreUneSessionUneSeuleFois() throws Exception {
		ComptesDeTest comptes = comptes();
		String email = comptes.utilisateurActif(comptes.role(true, "stock.voir"));
		Enrole enrole = enroler(email);
		String codeSecours = enrole.codesSecours().get(3);

		ClientHttp client = new ClientHttp(mvc);
		client.post("/api/v1/auth/login", ComptesDeTest.corpsConnexion(email)).andExpect(status().isAccepted());
		client.post("/api/v1/auth/2fa/verifier", corps(codeSecours.toLowerCase())).andExpect(status().isOk());
		client.get("/api/v1/auth/2fa/etat").andExpect(status().isOk()).andExpect(jsonPath("$.data.codes_secours_restants").value(9));

		ClientHttp autre = new ClientHttp(mvc);
		autre.post("/api/v1/auth/login", ComptesDeTest.corpsConnexion(email)).andExpect(status().isAccepted());
		autre.post("/api/v1/auth/2fa/verifier", corps(codeSecours)).andExpect(status().isUnauthorized());
	}

	@Test
	void cinqCodesFauxVerrouillentLeCompteMemeEnSeReconnectant() throws Exception {
		ComptesDeTest comptes = comptes();
		String email = comptes.utilisateurActif(comptes.role(true, "stock.voir"));
		enroler(email);

		// Se reconnecter entre deux essais ne remet pas le compteur à zéro.
		for (int i = 1; i <= 4; i++) {
			ClientHttp client = new ClientHttp(mvc);
			client.post("/api/v1/auth/login", ComptesDeTest.corpsConnexion(email)).andExpect(status().isAccepted());
			client.post("/api/v1/auth/2fa/verifier", corps("00000" + i)).andExpect(status().isUnauthorized());
		}
		ClientHttp dernier = new ClientHttp(mvc);
		dernier.post("/api/v1/auth/login", ComptesDeTest.corpsConnexion(email)).andExpect(status().isAccepted());
		dernier.post("/api/v1/auth/2fa/verifier", corps("000005")).andExpect(status().isLocked())
				.andExpect(jsonPath("$.code").value("COMPTE_VERROUILLE"));
		new ClientHttp(mvc).post("/api/v1/auth/login", ComptesDeTest.corpsConnexion(email)).andExpect(status().isLocked());
	}

	@Test
	void sansConnexionPreparatoireLesRoutesDuSecondFacteurSontFermees() throws Exception {
		ClientHttp anonyme = new ClientHttp(mvc);
		anonyme.post("/api/v1/auth/2fa/verifier", corps("123456")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("A2F_NON_DEMANDEE"));
		anonyme.get("/api/v1/auth/2fa/configuration").andExpect(status().isUnauthorized());
		anonyme.post("/api/v1/auth/2fa/activer", corps("123456")).andExpect(status().isUnauthorized());
		anonyme.post("/api/v1/auth/2fa/desactiver", "{\"mot_de_passe\":\"x\",\"code\":\"123456\"}").andExpect(status().isUnauthorized());
		anonyme.post("/api/v1/auth/2fa/codes-secours", corps("123456")).andExpect(status().isUnauthorized());
	}

	@Test
	void unRoleSansExigenceLaisseLeChoixEtPermetDeRetirerLeSecondFacteur() throws Exception {
		ComptesDeTest comptes = comptes();
		String email = comptes.utilisateurActif(comptes.role(false, "stock.voir"));

		// Connexion directe : le second facteur est facultatif.
		ClientHttp client = comptes.connecter(email);
		client.get("/api/v1/auth/2fa/etat").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.active").value(false)).andExpect(jsonPath("$.data.requise").value(false));

		String secret = JsonPath.read(client.get("/api/v1/auth/2fa/configuration").andReturn().getResponse().getContentAsString(), "$.data.secret");
		String reponse = client.post("/api/v1/auth/2fa/activer", corps(code(secret, 0))).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		List<String> codes = JsonPath.read(reponse, "$.data.codes_secours");
		client.get("/api/v1/auth/2fa/configuration").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("A2F_DEJA_ACTIVE"));

		// Désormais la connexion passe par le second facteur.
		new ClientHttp(mvc).post("/api/v1/auth/login", ComptesDeTest.corpsConnexion(email)).andExpect(status().isAccepted());

		client.post("/api/v1/auth/2fa/desactiver", "{\"mot_de_passe\":\"incorrect\",\"code\":\"%s\"}".formatted(codes.get(0)))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MOT_DE_PASSE_INCORRECT"));
		client.post("/api/v1/auth/2fa/desactiver", "{\"mot_de_passe\":\"%s\",\"code\":\"000000\"}".formatted(AuthEtUtilisateursTest.MOT_DE_PASSE_ADMIN))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CODE_A2F_INVALIDE"));
		client.post("/api/v1/auth/2fa/desactiver", "{\"mot_de_passe\":\"%s\",\"code\":\"%s\"}".formatted(AuthEtUtilisateursTest.MOT_DE_PASSE_ADMIN, codes.get(0)))
				.andExpect(status().isNoContent());
		comptes.connecter(email);
		assertThat(jdbc.sql("SELECT count(*) FROM codes_secours WHERE utilisateur_id = :u").param("u", comptes.id(email)).query(Long.class).single()).isZero();
	}

	@Test
	void unRoleQuiExigeLeSecondFacteurNePermetPasDeLeRetirer() throws Exception {
		ComptesDeTest comptes = comptes();
		String email = comptes.utilisateurActif(comptes.role(true, "stock.voir"));
		Enrole enrole = enroler(email);
		enrole.client().post("/api/v1/auth/2fa/desactiver", "{\"mot_de_passe\":\"%s\",\"code\":\"%s\"}"
						.formatted(AuthEtUtilisateursTest.MOT_DE_PASSE_ADMIN, enrole.codesSecours().get(0)))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("A2F_OBLIGATOIRE"));
	}

	@Test
	void lesCodesDeSecoursSeRegenerentSurPresentationDUnCodeValide() throws Exception {
		ComptesDeTest comptes = comptes();
		String email = comptes.utilisateurActif(comptes.role(true, "stock.voir"));
		Enrole enrole = enroler(email);
		String ancien = enrole.codesSecours().get(1);

		enrole.client().post("/api/v1/auth/2fa/codes-secours", corps("111111")).andExpect(status().isUnprocessableEntity());
		String reponse = enrole.client().post("/api/v1/auth/2fa/codes-secours", corps(enrole.codesSecours().get(0))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.codes_secours", hasSize(10))).andReturn().getResponse().getContentAsString();
		List<String> nouveaux = JsonPath.read(reponse, "$.data.codes_secours");
		assertThat(nouveaux).doesNotContain(ancien);

		ClientHttp client = new ClientHttp(mvc);
		client.post("/api/v1/auth/login", ComptesDeTest.corpsConnexion(email)).andExpect(status().isAccepted());
		client.post("/api/v1/auth/2fa/verifier", corps(ancien)).andExpect(status().isUnauthorized());
		client.post("/api/v1/auth/2fa/verifier", corps(nouveaux.get(0))).andExpect(status().isOk());
	}

	@Test
	void lAdministrateurReinitialiseLeSecondFacteurDUnAutreUtilisateur() throws Exception {
		ComptesDeTest comptes = comptes();
		String email = comptes.utilisateurActif(comptes.role(true, "stock.voir"));
		Enrole enrole = enroler(email);
		ClientHttp admin = comptes.admin();
		Long id = comptes.id(email);

		admin.post("/api/v1/utilisateurs/" + id + "/reinitialiser-a2f", "{}").andExpect(status().isNoContent());
		// La session en cours est fermée, le secret et les codes effacés : il faut tout refaire.
		enrole.client().get("/api/v1/auth/me").andExpect(status().isUnauthorized());
		assertThat(jdbc.sql("SELECT a2f_active_le FROM utilisateurs WHERE id = :i").param("i", id).query(java.sql.Timestamp.class).optional()).isEmpty();
		new ClientHttp(mvc).post("/api/v1/auth/login", ComptesDeTest.corpsConnexion(email))
				.andExpect(status().isAccepted()).andExpect(jsonPath("$.data.etape").value("CONFIGURATION"));
		admin.post("/api/v1/utilisateurs/" + id + "/reinitialiser-a2f", "{}").andExpect(status().isUnprocessableEntity());

		assertThat(jdbc.sql("SELECT count(*) FROM journal_audit WHERE action = 'utilisateur.a2f_reinitialisee' AND objet_id = :i")
				.param("i", String.valueOf(id)).query(Long.class).single()).isEqualTo(1);
		// L'utilisateur est prévenu.
		assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE destinataire_id = :i AND type = 'a2f.reinitialisee'")
				.param("i", id).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void personneNePeutReinitialiserSonPropreSecondFacteur() throws Exception {
		ComptesDeTest comptes = comptes();
		String email = comptes.utilisateurActif(comptes.role(true, "administration.voir", "administration.modifier"));
		Enrole enrole = enroler(email);
		enrole.client().post("/api/v1/utilisateurs/" + comptes.id(email) + "/reinitialiser-a2f", "{}")
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("A2F_AUTO_REINITIALISATION"));
	}

	@Test
	void lesRolesLivresExigentLeSecondFacteurDansLaMigrationEtLeCatalogue() {
		// Les tests créent les rôles livrés sans l'exigence ; la règle du SRS se lit dans le catalogue de défaut.
		assertThat(bj.vitraco.api.adm.droits.CatalogueDroits.rolesParDefaut().stream()
				.filter(r -> r.doubleAuthentificationRequise()).map(r -> r.code()).toList())
				.containsExactlyInAnyOrder("admin", "direction", "comptabilite_caisse");
	}

}
