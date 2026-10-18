package bj.vitraco.api.adm;

import bj.vitraco.api.TestcontainersConfiguration;
import bj.vitraco.api.TestcontainersConfiguration.UsageSimule;
import bj.vitraco.api.adm.emplacements.PerimetreService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class EmplacementsTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	UsageSimule usage;
	@Autowired
	PerimetreService perimetres;

	private ClientHttp admin() throws Exception {
		ClientHttp client = new ClientHttp(mvc);
		client.post("/api/v1/auth/login", "{\"email\":\"admin@vitraco.bj\",\"mot_de_passe\":\"" + AuthEtUtilisateursTest.MOT_DE_PASSE_ADMIN + "\"}")
				.andExpect(status().isOk());
		return client;
	}

	private static String code() {
		return "E" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
	}

	private static String magasin(String code) {
		return """
				{"code":"%s","nom":"Magasin %s","type":"MAGASIN","adresse":"Cotonou","responsable_nom":"Koffi A.","email":"mag@vitraco.bj","telephone":"97000000"}"""
				.formatted(code, code);
	}

	private static String boutiqueA(String code) {
		return """
				{"code":"%s","nom":"Boutique %s","type":"BOUTIQUE","mode_boutique":"A","responsable_nom":"Awa B.","email":"b@vitraco.bj","telephone":"96000000"}"""
				.formatted(code, code);
	}

	private static String boutiqueB(String code, Long magasinId) {
		return """
				{"code":"%s","nom":"Boutique %s","type":"BOUTIQUE","mode_boutique":"B","magasin_rattachement_id":%d,"responsable_nom":"Awa B.","email":"b@vitraco.bj","telephone":"96000000"}"""
				.formatted(code, code, magasinId);
	}

	private Long idDe(String code) {
		return jdbc.sql("SELECT id FROM emplacements WHERE code = :c").param("c", code).query(Long.class).single();
	}

	@Test
	void lEmplacementTransitEstCreeParLeSystemeEtIntouchable() throws Exception {
		assertThat(jdbc.sql("SELECT systeme FROM emplacements WHERE code = 'TRANSIT'").query(Boolean.class).single()).isTrue();
		Long id = idDe("TRANSIT");
		ClientHttp admin = admin();
		admin.put("/api/v1/emplacements/" + id, """
				{"code":"TRANSIT","nom":"Autre","type":"TRANSIT","responsable_nom":"x","email":"x@x.bj","telephone":"1"}""")
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("EMPLACEMENT_SYSTEME"));
		admin.post("/api/v1/emplacements/" + id + "/desactiver", "{}")
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("EMPLACEMENT_SYSTEME"));
	}

	@Test
	void unMagasinEtUneBoutiqueSeCreentEtLeCodeEstUnique() throws Exception {
		ClientHttp admin = admin();
		String codeMag = code();
		admin.post("/api/v1/emplacements", magasin(codeMag.toLowerCase()))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.data.code").value(codeMag))
				.andExpect(jsonPath("$.data.type").value("MAGASIN")).andExpect(jsonPath("$.data.actif").value(true));
		admin.post("/api/v1/emplacements", magasin(codeMag))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.code[0]").exists());

		String codeBtq = code();
		admin.post("/api/v1/emplacements", boutiqueA(codeBtq))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.data.mode_boutique").value("A"));

		admin.get("/api/v1/emplacements?search=" + codeMag).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.meta.total").value(1));
		assertThat(jdbc.sql("SELECT count(*) FROM journal_audit WHERE action = 'emplacement.cree' AND objet_id = :i")
				.param("i", String.valueOf(idDe(codeMag))).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void leTypeDepotEtTransitNeSeCreePasALaMain() throws Exception {
		admin().post("/api/v1/emplacements", """
				{"code":"%s","nom":"Dépôt","type":"DEPOT_REVENDEUR","responsable_nom":"x","email":"x@x.bj","telephone":"1"}""".formatted(code()))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.type[0]").exists());
	}

	@Test
	void leModeDeLaBoutiqueCommandeSonRattachementAuMagasin() throws Exception {
		ClientHttp admin = admin();
		String codeMag = code();
		admin.post("/api/v1/emplacements", magasin(codeMag)).andExpect(status().isCreated());
		Long magasinId = idDe(codeMag);

		// Mode B sans magasin, ou avec un magasin inconnu, ou avec une boutique : refusé.
		admin.post("/api/v1/emplacements", boutiqueA(code()).replace("\"A\"", "\"B\""))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.magasin_rattachement_id[0]").exists());
		admin.post("/api/v1/emplacements", boutiqueB(code(), 999999L))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.magasin_rattachement_id[0]").exists());
		String codeA = code();
		admin.post("/api/v1/emplacements", boutiqueA(codeA)).andExpect(status().isCreated());
		admin.post("/api/v1/emplacements", boutiqueB(code(), idDe(codeA)))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.magasin_rattachement_id[0]").exists());

		String codeB = code();
		admin.post("/api/v1/emplacements", boutiqueB(codeB, magasinId))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.data.mode_boutique").value("B"))
				.andExpect(jsonPath("$.data.magasin_rattachement.code").value(codeMag));

		// Une boutique en mode A ne porte aucun rattachement ; un magasin n'a pas de mode.
		admin.post("/api/v1/emplacements", boutiqueA(code()).replace("\"mode_boutique\":\"A\"", "\"mode_boutique\":\"A\",\"magasin_rattachement_id\":" + magasinId))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.data.magasin_rattachement").doesNotExist());
		admin.post("/api/v1/emplacements", magasin(code()).replace("\"type\":\"MAGASIN\"", "\"type\":\"MAGASIN\",\"mode_boutique\":\"A\""))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.mode_boutique[0]").exists());
	}

	@Test
	void leModeNeChangePasTantQueLaBoutiqueEstUtilisee() throws Exception {
		ClientHttp admin = admin();
		String codeMag = code();
		admin.post("/api/v1/emplacements", magasin(codeMag)).andExpect(status().isCreated());
		String codeBtq = code();
		admin.post("/api/v1/emplacements", boutiqueA(codeBtq)).andExpect(status().isCreated());
		Long id = idDe(codeBtq);
		String versB = boutiqueB(codeBtq, idDe(codeMag));

		usage.marquerEnUsage(id);
		try {
			admin.put("/api/v1/emplacements/" + id, versB)
					.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("EMPLACEMENT_MODE_BLOQUE"));
			// Le reste de la fiche se modifie quand même.
			admin.put("/api/v1/emplacements/" + id, boutiqueA(codeBtq).replace("Boutique", "Succursale"))
					.andExpect(status().isOk()).andExpect(jsonPath("$.data.nom").value("Succursale " + codeBtq));
		} finally {
			usage.liberer(id);
		}
		admin.put("/api/v1/emplacements/" + id, versB).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.mode_boutique").value("B"));
	}

	@Test
	void leCodeEtLeTypeSontFigesApresCreation() throws Exception {
		ClientHttp admin = admin();
		String c = code();
		admin.post("/api/v1/emplacements", magasin(c)).andExpect(status().isCreated());
		Long id = idDe(c);
		admin.put("/api/v1/emplacements/" + id, magasin(code()))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.code[0]").exists());
		admin.put("/api/v1/emplacements/" + id, boutiqueA(c))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.type[0]").exists());
	}

	@Test
	void desactiverRefuseSiLEmplacementServEncoreOuSiDesBoutiquesLUtilisent() throws Exception {
		ClientHttp admin = admin();
		String codeMag = code();
		admin.post("/api/v1/emplacements", magasin(codeMag)).andExpect(status().isCreated());
		Long magasinId = idDe(codeMag);
		String codeBtq = code();
		admin.post("/api/v1/emplacements", boutiqueB(codeBtq, magasinId)).andExpect(status().isCreated());
		Long boutiqueId = idDe(codeBtq);

		admin.post("/api/v1/emplacements/" + magasinId + "/desactiver", "{}")
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MAGASIN_RATTACHE"));

		usage.marquerEnUsage(boutiqueId);
		try {
			admin.post("/api/v1/emplacements/" + boutiqueId + "/desactiver", "{}")
					.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("EMPLACEMENT_EN_USAGE"));
		} finally {
			usage.liberer(boutiqueId);
		}

		admin.post("/api/v1/emplacements/" + boutiqueId + "/desactiver", "{}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.actif").value(false));
		admin.post("/api/v1/emplacements/" + boutiqueId + "/desactiver", "{}")
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DEJA_DESACTIVE"));
		admin.post("/api/v1/emplacements/" + magasinId + "/desactiver", "{}").andExpect(status().isOk());

		// Réactiver la boutique tant que son magasin est éteint est refusé ; il n'y a pas de suppression.
		admin.post("/api/v1/emplacements/" + boutiqueId + "/reactiver", "{}")
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MAGASIN_INACTIF"));
		admin.post("/api/v1/emplacements/" + magasinId + "/reactiver", "{}").andExpect(status().isOk());
		admin.post("/api/v1/emplacements/" + boutiqueId + "/reactiver", "{}").andExpect(status().isOk());
		admin.delete("/api/v1/emplacements/" + magasinId).andExpect(status().is4xxClientError());
		assertThat(jdbc.sql("SELECT count(*) FROM emplacements WHERE id = :i").param("i", magasinId).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void laBaseRefuseLesCombinaisonsIncoherentesMemeSansPasserParLApi() {
		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO emplacements (code, nom, type, mode_boutique, systeme, actif)
				VALUES ('BD1', 'x', 'MAGASIN', 'A', false, true)""").update())
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO emplacements (code, nom, type, mode_boutique, systeme, actif)
				VALUES ('BD2', 'x', 'BOUTIQUE', 'B', false, true)""").update())
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO emplacements (code, nom, type, systeme, actif)
				VALUES ('transit', 'x', 'MAGASIN', false, true)""").update())
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void lesEcrituresDemandentLesDroitsDAdministration() throws Exception {
		ClientHttp admin = admin();
		String email = "mag-" + UUID.randomUUID().toString().substring(0, 8) + "@vitraco.bj";
		admin.post("/api/v1/utilisateurs", "{\"nom\":\"Mag\",\"email\":\"%s\",\"roles\":[\"magasin\"]}".formatted(email))
				.andExpect(status().isCreated());
		// Le rôle « magasin » n'a aucun droit d'administration : il ne voit même pas la liste.
		jdbc.sql("UPDATE utilisateurs SET statut = 'ACTIF', mot_de_passe_hash = (SELECT mot_de_passe_hash FROM utilisateurs WHERE email = 'admin@vitraco.bj') WHERE email = :e")
				.param("e", email).update();
		ClientHttp magasinier = new ClientHttp(mvc);
		magasinier.post("/api/v1/auth/login", "{\"email\":\"%s\",\"mot_de_passe\":\"%s\"}".formatted(email, AuthEtUtilisateursTest.MOT_DE_PASSE_ADMIN))
				.andExpect(status().isOk());
		magasinier.get("/api/v1/emplacements").andExpect(status().isForbidden());
		// Seule la version courte, sans coordonnées, est ouverte à tous pour alimenter les filtres.
		magasinier.get("/api/v1/emplacements/choix?type=TRANSIT").andExpect(status().isOk()).andExpect(jsonPath("$.data[0].code").value("TRANSIT"))
				.andExpect(jsonPath("$.data[0].email").doesNotExist());
		magasinier.post("/api/v1/emplacements", magasin(code())).andExpect(status().isForbidden());
	}

	@Test
	void lePerimetreDUnUtilisateurVientDeSesEmplacementsOuDeSonRoleConsolide() throws Exception {
		ClientHttp admin = admin();
		String codeMag = code();
		String codeBtq = code();
		admin.post("/api/v1/emplacements", magasin(codeMag)).andExpect(status().isCreated());
		admin.post("/api/v1/emplacements", boutiqueA(codeBtq)).andExpect(status().isCreated());
		Long magasinId = idDe(codeMag);
		Long boutiqueId = idDe(codeBtq);
		Long transitId = idDe("TRANSIT");

		String email = "per-" + UUID.randomUUID().toString().substring(0, 8) + "@vitraco.bj";
		admin.post("/api/v1/utilisateurs", """
				{"nom":"Per","email":"%s","roles":["magasin"],"emplacements":[%d,%d]}""".formatted(email, magasinId, boutiqueId))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.perimetre_global").value(false))
				.andExpect(jsonPath("$.data.emplacements.length()").value(2));
		Long userId = jdbc.sql("SELECT id FROM utilisateurs WHERE email = :e").param("e", email).query(Long.class).single();

		var perimetre = perimetres.perimetreDe(userId);
		assertThat(perimetre.global()).isFalse();
		assertThat(perimetre.emplacementIds()).containsExactlyInAnyOrder(magasinId, boutiqueId);
		assertThat(perimetre.autorise(transitId)).isFalse();

		// Un emplacement désactivé sort du périmètre.
		admin.post("/api/v1/emplacements/" + boutiqueId + "/desactiver", "{}").andExpect(status().isOk());
		assertThat(perimetres.perimetreDe(userId).emplacementIds()).containsExactly(magasinId);

		// Le rôle Direction voit tout (EF-ADM-13), y compris ce qui n'est pas attribué.
		admin.put("/api/v1/utilisateurs/" + userId, """
				{"nom":"Per","email":"%s","roles":["direction"]}""".formatted(email))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.perimetre_global").value(true));
		var consolide = perimetres.perimetreDe(userId);
		assertThat(consolide.global()).isTrue();
		assertThat(consolide.emplacementIds()).contains(magasinId, transitId).doesNotContain(boutiqueId);
	}

	@Test
	void seulsUnMagasinOuUneBoutiqueActifsSAttribuentAUnUtilisateur() throws Exception {
		ClientHttp admin = admin();
		String email = "att-" + UUID.randomUUID().toString().substring(0, 8) + "@vitraco.bj";
		String corps = "{\"nom\":\"Att\",\"email\":\"%s\",\"roles\":[\"magasin\"],\"emplacements\":[%d]}";

		admin.post("/api/v1/utilisateurs", corps.formatted(email, idDe("TRANSIT")))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.emplacements[0]").exists());
		admin.post("/api/v1/utilisateurs", corps.formatted(email, 999999L))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.emplacements[0]").exists());

		String c = code();
		admin.post("/api/v1/emplacements", magasin(c)).andExpect(status().isCreated());
		Long id = idDe(c);
		admin.post("/api/v1/emplacements/" + id + "/desactiver", "{}").andExpect(status().isOk());
		admin.post("/api/v1/utilisateurs", corps.formatted(email, id))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.emplacements[0]").exists());
	}

	@Test
	void leRoleDirectionEtAdminSontConsolidesEtLeFlagSeLitEtSEcritSurLesRoles() throws Exception {
		assertThat(jdbc.sql("SELECT code FROM roles WHERE perimetre_global ORDER BY code").query(String.class).list())
				.contains("admin", "direction").doesNotContain("magasin", "boutique", "commercial");

		ClientHttp admin = admin();
		String code = "role_" + UUID.randomUUID().toString().substring(0, 8);
		admin.post("/api/v1/roles", "{\"code\":\"%s\",\"libelle\":\"Auditeur\",\"permissions\":[\"stock.voir\"],\"perimetre_global\":true}".formatted(code))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.data.perimetre_global").value(true));
		Long id = jdbc.sql("SELECT id FROM roles WHERE code = :c").param("c", code).query(Long.class).single();
		// Omis dans une modification : la valeur est conservée.
		admin.put("/api/v1/roles/" + id, "{\"libelle\":\"Auditeur\",\"permissions\":[\"stock.voir\"]}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.perimetre_global").value(true));
		admin.put("/api/v1/roles/" + id, "{\"libelle\":\"Auditeur\",\"permissions\":[\"stock.voir\"],\"perimetre_global\":false}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.perimetre_global").value(false));
	}

}
