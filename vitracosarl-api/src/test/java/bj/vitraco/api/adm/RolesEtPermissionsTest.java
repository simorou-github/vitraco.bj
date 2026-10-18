package bj.vitraco.api.adm;

import bj.vitraco.api.TestcontainersConfiguration;
import bj.vitraco.api.TestcontainersConfiguration.LiensCaptures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RolesEtPermissionsTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	LiensCaptures liens;

	private ClientHttp admin() throws Exception {
		ClientHttp client = new ClientHttp(mvc);
		client.post("/api/v1/auth/login", "{\"email\":\"admin@vitraco.bj\",\"mot_de_passe\":\"" + AuthEtUtilisateursTest.MOT_DE_PASSE_ADMIN + "\"}")
				.andExpect(status().isOk());
		return client;
	}

	private List<String> permissionsDuRole(String code) {
		return jdbc.sql("""
				SELECT p.code FROM roles r JOIN role_permission rp ON rp.role_id = r.id
				JOIN permissions p ON p.id = rp.permission_id WHERE r.code = :c
				""").param("c", code).query(String.class).list();
	}

	@Test
	void lesHuitRolesDuSrsSontLivresAvecLaMatriceParDefaut() {
		assertThat(jdbc.sql("SELECT code FROM roles WHERE systeme").query(String.class).list())
				.contains("admin", "direction", "responsable_commercial", "commercial",
						"comptabilite_caisse", "magasin", "controleur", "boutique");

		// Lecture seule (L), créer et modifier (C), créer et valider (CV), aucun accès (-).
		assertThat(permissionsDuRole("commercial")).contains("commandes.creer", "commandes.modifier", "clients.creer", "rapports.voir")
				.doesNotContain("commandes.valider", "administration.voir", "prix_achat.voir", "marges.voir");
		assertThat(permissionsDuRole("magasin")).contains("receptions.creer", "receptions.valider", "transferts.valider", "stock.modifier")
				.doesNotContain("factures.voir", "clients.voir");
		assertThat(permissionsDuRole("direction")).contains("commandes.valider", "prix_achat.voir", "marges.voir", "inventaires.cloturer", "rapports.exporter")
				.doesNotContain("commandes.creer");
		assertThat(permissionsDuRole("controleur")).contains("anomalies.valider", "anomalies.cloturer").doesNotContain("reglements.creer");
		assertThat(permissionsDuRole("admin")).contains("administration.creer", "administration.annuler", "administration.exporter");
	}

	@Test
	void leCatalogueDePermissionsEstExposeAuFront() throws Exception {
		admin().get("/api/v1/permissions").andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].code").value(hasItem("factures.valider")))
				.andExpect(jsonPath("$.data[0].module").exists());
	}

	@Test
	void unRolePersonnaliseSeCreeSeModifieEtSeSupprimeSousConditions() throws Exception {
		ClientHttp admin = admin();
		String code = "role_" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");

		admin.post("/api/v1/roles", """
				{"code":"%s","libelle":"Stagiaire","description":"Lecture","permissions":["stock.voir","rapports.voir"]}""".formatted(code))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.data.systeme").value(false))
				.andExpect(jsonPath("$.data.permissions.length()").value(2));
		Long id = jdbc.sql("SELECT id FROM roles WHERE code = :c").param("c", code).query(Long.class).single();

		admin.post("/api/v1/roles", "{\"code\":\"%s\",\"libelle\":\"Doublon\",\"permissions\":[]}".formatted(code))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.code[0]").exists());
		admin.post("/api/v1/roles", "{\"code\":\"Mauvais Code\",\"libelle\":\"X\",\"permissions\":[]}")
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.code").exists());
		admin.post("/api/v1/roles", "{\"code\":\"%s_b\",\"libelle\":\"X\",\"permissions\":[\"nexiste.pas\"]}".formatted(code))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.permissions[0]").exists());

		admin.put("/api/v1/roles/" + id, "{\"libelle\":\"Stagiaire senior\",\"permissions\":[\"stock.voir\"]}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.libelle").value("Stagiaire senior"))
				.andExpect(jsonPath("$.data.permissions.length()").value(1)).andExpect(jsonPath("$.data.code").value(code));

		String email = "stagiaire." + UUID.randomUUID().toString().substring(0, 8) + "@test.vitraco.bj";
		admin.post("/api/v1/utilisateurs", "{\"nom\":\"S\",\"email\":\"%s\",\"roles\":[\"%s\"]}".formatted(email, code)).andExpect(status().isCreated());
		admin.delete("/api/v1/roles/" + id).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ROLE_UTILISE"));
	}

	@Test
	void unRoleLivreParDefautNeSeSupprimePas() throws Exception {
		Long id = jdbc.sql("SELECT id FROM roles WHERE code = 'boutique'").query(Long.class).single();
		admin().delete("/api/v1/roles/" + id).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ROLE_SYSTEME"));
	}

	@Test
	void unRoleInutiliseSeSupprimeEtLaSuppressionEstTracee() throws Exception {
		ClientHttp admin = admin();
		String code = "jetable_" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
		admin.post("/api/v1/roles", "{\"code\":\"%s\",\"libelle\":\"Jetable\",\"permissions\":[]}".formatted(code)).andExpect(status().isCreated());
		Long id = jdbc.sql("SELECT id FROM roles WHERE code = :c").param("c", code).query(Long.class).single();

		admin.delete("/api/v1/roles/" + id).andExpect(status().isNoContent());

		Long traces = jdbc.sql("SELECT count(*) FROM journal_audit WHERE action = 'role.supprime' AND objet_id = :i")
				.param("i", String.valueOf(id)).query(Long.class).single();
		assertThat(traces).isEqualTo(1);
	}

}
