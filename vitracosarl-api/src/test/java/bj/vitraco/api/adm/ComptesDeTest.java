package bj.vitraco.api.adm;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Comptes jetables pour les tests : créés par l'administrateur, activés en base, mot de passe connu. */
final class ComptesDeTest {

	private final MockMvc mvc;
	private final JdbcClient jdbc;

	ComptesDeTest(MockMvc mvc, JdbcClient jdbc) {
		this.mvc = mvc;
		this.jdbc = jdbc;
	}

	ClientHttp admin() throws Exception {
		return connecter("admin@vitraco.bj");
	}

	ClientHttp connecter(String email) throws Exception {
		ClientHttp client = new ClientHttp(mvc);
		client.post("/api/v1/auth/login", corpsConnexion(email)).andExpect(status().isOk());
		return client;
	}

	static String corpsConnexion(String email) {
		return "{\"email\":\"%s\",\"mot_de_passe\":\"%s\"}".formatted(email, AuthEtUtilisateursTest.MOT_DE_PASSE_ADMIN);
	}

	/** Crée un utilisateur actif avec ce rôle ; son mot de passe est celui de l'administrateur de test. */
	String utilisateurActif(String role) throws Exception {
		String email = role.replace('_', '-') + "-" + UUID.randomUUID().toString().substring(0, 8) + "@vitraco.bj";
		admin().post("/api/v1/utilisateurs", "{\"nom\":\"Test\",\"prenom\":\"Compte\",\"email\":\"%s\",\"roles\":[\"%s\"]}".formatted(email, role))
				.andExpect(status().isCreated());
		activer(email);
		return email;
	}

	void activer(String email) {
		jdbc.sql("UPDATE utilisateurs SET statut = 'ACTIF', mot_de_passe_hash = (SELECT mot_de_passe_hash FROM utilisateurs WHERE email = 'admin@vitraco.bj') WHERE email = :e")
				.param("e", email).update();
	}

	/** Un rôle personnalisé, avec ou sans l'exigence de double authentification. */
	String role(boolean a2fRequise, String... permissions) throws Exception {
		String code = "role_" + UUID.randomUUID().toString().substring(0, 8);
		String liste = String.join("\",\"", permissions);
		admin().post("/api/v1/roles", "{\"code\":\"%s\",\"libelle\":\"Rôle %s\",\"permissions\":[\"%s\"],\"double_authentification_requise\":%s}"
				.formatted(code, code, liste, a2fRequise)).andExpect(status().isCreated());
		return code;
	}

	Long id(String email) {
		return jdbc.sql("SELECT id FROM utilisateurs WHERE email = :e").param("e", email).query(Long.class).single();
	}

	/** Un utilisateur actif dont le périmètre est limité à ces emplacements. */
	String utilisateurActif(String role, Long... emplacements) throws Exception {
		String email = role.replace('_', '-') + "-" + UUID.randomUUID().toString().substring(0, 8) + "@vitraco.bj";
		String ids = java.util.Arrays.stream(emplacements).map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
		admin().post("/api/v1/utilisateurs", "{\"nom\":\"Test\",\"prenom\":\"Compte\",\"email\":\"%s\",\"roles\":[\"%s\"],\"emplacements\":[%s]}".formatted(email, role, ids))
				.andExpect(status().isCreated());
		activer(email);
		return email;
	}

	/** Un rôle personnalisé qui voit tous les emplacements. */
	String roleGlobal(String... permissions) throws Exception {
		String code = "role_" + UUID.randomUUID().toString().substring(0, 8);
		String liste = String.join("\",\"", permissions);
		admin().post("/api/v1/roles", "{\"code\":\"%s\",\"libelle\":\"Rôle %s\",\"permissions\":[\"%s\"],\"perimetre_global\":true}"
				.formatted(code, code, liste)).andExpect(status().isCreated());
		return code;
	}

}
