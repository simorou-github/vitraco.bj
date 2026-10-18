package bj.vitraco.api.adm;

import bj.vitraco.api.TestcontainersConfiguration;
import bj.vitraco.api.TestcontainersConfiguration.LiensCaptures;
import bj.vitraco.api.aud.JournalAudit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthEtUtilisateursTest {

	static final String MOT_DE_PASSE_ADMIN = "Admin#Test2026x";
	static final String MOT_DE_PASSE_VALIDE = "Solide#2026abc";

	@Autowired
	MockMvc mvc;
	@Autowired
	LiensCaptures liens;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	JournalAudit journal;

	private String emailUnique(String prefixe) {
		return prefixe + "." + UUID.randomUUID().toString().substring(0, 8) + "@test.vitraco.bj";
	}

	private ClientHttp connecte(String email, String motDePasse) throws Exception {
		ClientHttp client = new ClientHttp(mvc);
		client.post("/api/v1/auth/login", "{\"email\":\"%s\",\"mot_de_passe\":\"%s\"}".formatted(email, motDePasse))
				.andExpect(status().isOk());
		return client;
	}

	private ClientHttp admin() throws Exception {
		return connecte("admin@vitraco.bj", MOT_DE_PASSE_ADMIN);
	}

	/** Crée un compte par l'administrateur puis l'active, et renvoie son e-mail. */
	private String compteActif(ClientHttp admin, String role) throws Exception {
		String email = emailUnique(role);
		admin.post("/api/v1/utilisateurs", """
				{"nom":"Test","prenom":"%s","email":"%s","roles":["%s"]}""".formatted(role, email, role))
				.andExpect(status().isCreated());
		new ClientHttp(mvc).post("/api/v1/auth/activation",
				"{\"token\":\"%s\",\"mot_de_passe\":\"%s\"}".formatted(liens.jetonActivation(email), MOT_DE_PASSE_VALIDE))
				.andExpect(status().isNoContent());
		return email;
	}

	@Test
	void lAdministrateurAmorceSeConnecteEtRecoitSesPermissions() throws Exception {
		new ClientHttp(mvc).post("/api/v1/auth/login", "{\"email\":\"ADMIN@vitraco.bj\",\"mot_de_passe\":\"" + MOT_DE_PASSE_ADMIN + "\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.email").value("admin@vitraco.bj"))
				.andExpect(jsonPath("$.data.roles").value(hasItem("admin")))
				.andExpect(jsonPath("$.data.permissions").value(hasItems("administration.voir", "administration.creer")))
				.andExpect(jsonPath("$.data.statut").value("ACTIF"));
	}

	@Test
	void lEtatDeConnexionSurvitEntreLesRequetesEtPrendFinALaDeconnexion() throws Exception {
		ClientHttp admin = admin();
		admin.get("/api/v1/auth/me").andExpect(status().isOk()).andExpect(jsonPath("$.data.email").value("admin@vitraco.bj"));

		admin.post("/api/v1/auth/logout", "{}").andExpect(status().isNoContent());
		admin.get("/api/v1/auth/me").andExpect(status().isUnauthorized());
	}

	@Test
	void unMauvaisMotDePasseEtUnEmailInconnuDonnentLaMemeReponse() throws Exception {
		new ClientHttp(mvc).post("/api/v1/auth/login", "{\"email\":\"admin@vitraco.bj\",\"mot_de_passe\":\"faux\"}")
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("IDENTIFIANTS_INVALIDES"));
		new ClientHttp(mvc).post("/api/v1/auth/login", "{\"email\":\"inconnu@vitraco.bj\",\"mot_de_passe\":\"faux\"}")
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("IDENTIFIANTS_INVALIDES"));
	}

	@Test
	void uneEcritureSansJetonCsrfEstRefusee() throws Exception {
		new ClientHttp(mvc).postSansCsrf("/api/v1/auth/login", "{\"email\":\"admin@vitraco.bj\",\"mot_de_passe\":\"" + MOT_DE_PASSE_ADMIN + "\"}")
				.andExpect(status().isForbidden());
	}

	@Test
	void cinqEchecsVerrouillentLeCompteMemeAvecLeBonMotDePasseEtLAdministrateurLeDeverrouille() throws Exception {
		ClientHttp admin = admin();
		String email = compteActif(admin, "commercial");

		for (int i = 0; i < 5; i++) {
			new ClientHttp(mvc).post("/api/v1/auth/login", "{\"email\":\"%s\",\"mot_de_passe\":\"faux\"}".formatted(email))
					.andExpect(status().isUnauthorized());
		}
		new ClientHttp(mvc).post("/api/v1/auth/login", "{\"email\":\"%s\",\"mot_de_passe\":\"%s\"}".formatted(email, MOT_DE_PASSE_VALIDE))
				.andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("COMPTE_VERROUILLE"));

		Long id = jdbc.sql("SELECT id FROM utilisateurs WHERE email = :e").param("e", email).query(Long.class).single();
		admin.post("/api/v1/utilisateurs/" + id + "/reactiver", "{}").andExpect(status().isOk());
		connecte(email, MOT_DE_PASSE_VALIDE);
	}

	@Test
	void unCompteSeCreeSansMotDePassePuisSActiveParLienEnRespectantLaPolitique() throws Exception {
		ClientHttp admin = admin();
		String email = emailUnique("magasin");
		admin.post("/api/v1/utilisateurs", """
				{"nom":"Dossou","prenom":"Marie","email":"%s","telephone":"97000000","roles":["magasin"]}""".formatted(email))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.statut").value("EN_ATTENTE_ACTIVATION"))
				.andExpect(jsonPath("$.data.cree_par.nom").value("Administrateur"));

		// Avant activation : aucune connexion possible.
		new ClientHttp(mvc).post("/api/v1/auth/login", "{\"email\":\"%s\",\"mot_de_passe\":\"%s\"}".formatted(email, MOT_DE_PASSE_VALIDE))
				.andExpect(status().isUnauthorized());

		String jeton = liens.jetonActivation(email);
		ClientHttp visiteur = new ClientHttp(mvc);
		visiteur.post("/api/v1/auth/activation", "{\"token\":\"%s\",\"mot_de_passe\":\"court\"}".formatted(jeton))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors.mot_de_passe[0]").exists());
		// Le jeton n'est pas brûlé par un mot de passe refusé.
		visiteur.post("/api/v1/auth/activation", "{\"token\":\"%s\",\"mot_de_passe\":\"%s\"}".formatted(jeton, MOT_DE_PASSE_VALIDE))
				.andExpect(status().isNoContent());
		// Usage unique.
		visiteur.post("/api/v1/auth/activation", "{\"token\":\"%s\",\"mot_de_passe\":\"%s\"}".formatted(jeton, MOT_DE_PASSE_VALIDE))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("LIEN_INVALIDE"));

		connecte(email, MOT_DE_PASSE_VALIDE).get("/api/v1/auth/me")
				.andExpect(jsonPath("$.data.permissions").value(hasItem("receptions.creer")))
				.andExpect(jsonPath("$.data.permissions").value(not(hasItem("administration.voir"))));
	}

	@Test
	void unUtilisateurSansLeDroitEstRefuseCoteServeur() throws Exception {
		ClientHttp admin = admin();
		ClientHttp commercial = connecte(compteActif(admin, "commercial"), MOT_DE_PASSE_VALIDE);

		commercial.get("/api/v1/utilisateurs").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCES_REFUSE"));
		commercial.post("/api/v1/utilisateurs", "{\"nom\":\"X\",\"email\":\"x@x.bj\",\"roles\":[\"magasin\"]}").andExpect(status().isForbidden());
		new ClientHttp(mvc).get("/api/v1/utilisateurs").andExpect(status().isUnauthorized());
	}

	@Test
	void uneAdresseDejaUtiliseeOuUnRoleInconnuSontRefuses() throws Exception {
		ClientHttp admin = admin();
		admin.post("/api/v1/utilisateurs", "{\"nom\":\"Doublon\",\"email\":\"Admin@Vitraco.bj\",\"roles\":[\"magasin\"]}")
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.email[0]").exists());
		admin.post("/api/v1/utilisateurs", "{\"nom\":\"Role\",\"email\":\"%s\",\"roles\":[\"inexistant\"]}".formatted(emailUnique("r")))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.roles[0]").exists());
		admin.post("/api/v1/utilisateurs", "{\"nom\":\"\",\"email\":\"pas-un-email\",\"roles\":[]}")
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors.nom").exists()).andExpect(jsonPath("$.errors.email").exists()).andExpect(jsonPath("$.errors.roles").exists());
	}

	@Test
	void suspendreUnCompteFermeSaSessionImmediatement() throws Exception {
		ClientHttp admin = admin();
		String email = compteActif(admin, "boutique");
		ClientHttp boutique = connecte(email, MOT_DE_PASSE_VALIDE);
		boutique.get("/api/v1/auth/me").andExpect(status().isOk());

		Long id = jdbc.sql("SELECT id FROM utilisateurs WHERE email = :e").param("e", email).query(Long.class).single();
		admin.post("/api/v1/utilisateurs/" + id + "/suspendre", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.data.statut").value("SUSPENDU"));

		boutique.get("/api/v1/auth/me").andExpect(status().isUnauthorized());
		new ClientHttp(mvc).post("/api/v1/auth/login", "{\"email\":\"%s\",\"mot_de_passe\":\"%s\"}".formatted(email, MOT_DE_PASSE_VALIDE))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COMPTE_SUSPENDU"));

		admin.post("/api/v1/utilisateurs/" + id + "/reactiver", "{}").andExpect(jsonPath("$.data.statut").value("ACTIF"));
		connecte(email, MOT_DE_PASSE_VALIDE);
	}

	@Test
	void unAdministrateurNePeutPasSuspendreSonPropreCompte() throws Exception {
		ClientHttp admin = admin();
		Long id = jdbc.sql("SELECT id FROM utilisateurs WHERE email = 'admin@vitraco.bj'").query(Long.class).single();
		admin.post("/api/v1/utilisateurs/" + id + "/suspendre", "{}")
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SUSPENSION_PROPRE_COMPTE"));
	}

	@Test
	void changerLesRolesFermeLaSessionPourQueLesDroitsSAppliquent() throws Exception {
		ClientHttp admin = admin();
		String email = compteActif(admin, "magasin");
		ClientHttp utilisateur = connecte(email, MOT_DE_PASSE_VALIDE);
		Long id = jdbc.sql("SELECT id FROM utilisateurs WHERE email = :e").param("e", email).query(Long.class).single();

		admin.put("/api/v1/utilisateurs/" + id, "{\"nom\":\"Test\",\"email\":\"%s\",\"roles\":[\"controleur\"]}".formatted(email))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.roles[0]").value("controleur"));

		utilisateur.get("/api/v1/auth/me").andExpect(status().isUnauthorized());
		connecte(email, MOT_DE_PASSE_VALIDE).get("/api/v1/auth/me").andExpect(jsonPath("$.data.roles[0]").value("controleur"));
	}

	@Test
	void lesActionsSensiblesSontAuJournalAvecLeursValeursEtLAuteur() throws Exception {
		ClientHttp admin = admin();
		String email = emailUnique("journal");
		admin.post("/api/v1/utilisateurs", "{\"nom\":\"Trace\",\"email\":\"%s\",\"roles\":[\"commercial\"]}".formatted(email))
				.andExpect(status().isCreated());

		var ligne = jdbc.sql("""
				SELECT action, utilisateur_libelle, nouvelle_valeur->>'email' AS email, nouvelle_valeur->'roles'->>0 AS role, adresse_ip
				FROM journal_audit WHERE action = 'utilisateur.cree' AND nouvelle_valeur->>'email' = :e
				""").param("e", email).query().singleRow();
		assertThat(ligne).containsEntry("utilisateur_libelle", "Administrateur").containsEntry("role", "commercial");
		assertThat(journal.premiereAlteration()).isEmpty();

		// Aucune clé de mot de passe ou d'empreinte dans les traces.
		Long avecSecret = jdbc.sql("""
						SELECT count(*) FROM journal_audit
						WHERE jsonb_exists_any(coalesce(nouvelle_valeur, '{}'::jsonb) || coalesce(ancienne_valeur, '{}'::jsonb),
						                       ARRAY['mot_de_passe', 'motDePasse', 'mot_de_passe_hash', 'motDePasseHash', 'password', 'hash'])
						""")
				.query(Long.class).single();
		assertThat(avecSecret).isZero();
	}

	@Test
	void laReinitialisationDuMotDePasseParLienFonctionneEtFermeLesSessions() throws Exception {
		ClientHttp admin = admin();
		String email = compteActif(admin, "comptabilite_caisse");
		ClientHttp ouverte = connecte(email, MOT_DE_PASSE_VALIDE);

		new ClientHttp(mvc).post("/api/v1/auth/mot-de-passe/oublie", "{\"email\":\"%s\"}".formatted(email)).andExpect(status().isNoContent());
		// Même réponse pour une adresse inconnue.
		new ClientHttp(mvc).post("/api/v1/auth/mot-de-passe/oublie", "{\"email\":\"personne@vitraco.bj\"}").andExpect(status().isNoContent());

		String nouveau = "Nouveau#2026xyz";
		new ClientHttp(mvc).post("/api/v1/auth/mot-de-passe/reinitialiser",
				"{\"token\":\"%s\",\"mot_de_passe\":\"%s\"}".formatted(liens.jetonReinitialisation(email), nouveau)).andExpect(status().isNoContent());

		ouverte.get("/api/v1/auth/me").andExpect(status().isUnauthorized());
		new ClientHttp(mvc).post("/api/v1/auth/login", "{\"email\":\"%s\",\"mot_de_passe\":\"%s\"}".formatted(email, MOT_DE_PASSE_VALIDE))
				.andExpect(status().isUnauthorized());
		connecte(email, nouveau);
	}

	@Test
	void laListeSeFiltreEtSePagineAvecLEnveloppeDuFront() throws Exception {
		ClientHttp admin = admin();
		String email = compteActif(admin, "controleur");

		admin.get("/api/v1/utilisateurs?search=" + email.substring(0, 12) + "&role=controleur&statut=ACTIF&page=1&per_page=5")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].email").value(email))
				.andExpect(jsonPath("$.meta.current_page").value(1))
				.andExpect(jsonPath("$.meta.per_page").value(5))
				.andExpect(jsonPath("$.meta.total").value(1))
				.andExpect(jsonPath("$.meta.last_page").value(1));
	}

	@Test
	void uneConnexionReussieEstJournaliseeAuNomDeLaPersonneConnectee() throws Exception {
		ClientHttp client = new ClientHttp(mvc);
		client.post("/api/v1/auth/login", "{\"email\":\"admin@vitraco.bj\",\"mot_de_passe\":\"" + MOT_DE_PASSE_ADMIN + "\"}")
				.andExpect(status().isOk());
		Long adminId = jdbc.sql("SELECT id FROM utilisateurs WHERE email = 'admin@vitraco.bj'").query(Long.class).single();
		assertThat(jdbc.sql("""
				SELECT utilisateur_id FROM journal_audit WHERE action = 'auth.connexion' ORDER BY rang DESC LIMIT 1""")
				.query(Long.class).single()).isEqualTo(adminId);
		assertThat(jdbc.sql("""
				SELECT utilisateur_libelle FROM journal_audit WHERE action = 'auth.connexion' ORDER BY rang DESC LIMIT 1""")
				.query(String.class).single()).isNotBlank();
	}

}
