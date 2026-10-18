package bj.vitraco.api.adm;

import bj.vitraco.api.TestcontainersConfiguration;
import bj.vitraco.api.adm.parametres.CatalogueParametres;
import bj.vitraco.api.adm.parametres.ParametreService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ReferentielsEtParametresTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	ParametreService parametres;

	private ClientHttp admin() throws Exception {
		return connecte("admin@vitraco.bj");
	}

	private ClientHttp connecte(String email) throws Exception {
		ClientHttp client = new ClientHttp(mvc);
		client.post("/api/v1/auth/login", "{\"email\":\"%s\",\"mot_de_passe\":\"%s\"}".formatted(email, AuthEtUtilisateursTest.MOT_DE_PASSE_ADMIN))
				.andExpect(status().isOk());
		return client;
	}

	/** Un compte actif avec le rôle voulu, qui se connecte avec le mot de passe de l'administrateur de test. */
	private ClientHttp utilisateurAvecRole(String role) throws Exception {
		ClientHttp admin = admin();
		String email = role + "-" + UUID.randomUUID().toString().substring(0, 8) + "@vitraco.bj";
		admin.post("/api/v1/utilisateurs", "{\"nom\":\"Test\",\"email\":\"%s\",\"roles\":[\"%s\"]}".formatted(email, role)).andExpect(status().isCreated());
		jdbc.sql("UPDATE utilisateurs SET statut = 'ACTIF', mot_de_passe_hash = (SELECT mot_de_passe_hash FROM utilisateurs WHERE email = 'admin@vitraco.bj') WHERE email = :e")
				.param("e", email).update();
		return connecte(email);
	}

	private static String code() {
		return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
	}

	@Test
	void lesModesDePaiementEtLesCategoriesDuSrsSontLivres() throws Exception {
		assertThat(jdbc.sql("SELECT code FROM modes_paiement WHERE systeme").query(String.class).list())
				.contains("ESPECES", "CHEQUE", "VIREMENT", "MOBILE_MONEY", "AUTRE");
		assertThat(jdbc.sql("SELECT type_dgi FROM modes_paiement WHERE code = 'MOBILE_MONEY'").query(String.class).single()).isEqualTo("MOBILEMONEY");
		assertThat(jdbc.sql("SELECT count(*) FROM categories_depense").query(Long.class).single()).isGreaterThanOrEqualTo(12);
		admin().get("/api/v1/categories-depense?per_page=50").andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].code").value(hasItem("DOUANE_TRANSIT")));
	}

	@Test
	void uneBanqueSeCreeSeRenommeSeDesactiveEtNeSeSupprimePas() throws Exception {
		ClientHttp admin = admin();
		String code = code();
		admin.post("/api/v1/banques", "{\"code\":\"%s\",\"libelle\":\"Banque Atlantique\"}".formatted(code.toLowerCase()))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.data.code").value(code)).andExpect(jsonPath("$.data.actif").value(true));
		Long id = jdbc.sql("SELECT id FROM banques WHERE code = :c").param("c", code).query(Long.class).single();

		admin.post("/api/v1/banques", "{\"code\":\"%s\",\"libelle\":\"Doublon\"}".formatted(code))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.code[0]").exists());
		admin.post("/api/v1/banques", "{\"code\":\"a b\",\"libelle\":\"X\"}")
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.code").exists());

		admin.put("/api/v1/banques/" + id, "{\"code\":\"%s\",\"libelle\":\"Atlantique Bénin\"}".formatted(code))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.libelle").value("Atlantique Bénin"));
		admin.put("/api/v1/banques/" + id, "{\"code\":\"AUTRE_CODE\",\"libelle\":\"X\"}")
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.code[0]").exists());

		admin.post("/api/v1/banques/" + id + "/desactiver", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.data.actif").value(false));
		admin.post("/api/v1/banques/" + id + "/desactiver", "{}").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("DEJA_DESACTIVE"));
		admin.get("/api/v1/banques?actif=true&search=" + code).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
		admin.get("/api/v1/banques?actif=false&search=" + code).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
		admin.post("/api/v1/banques/" + id + "/reactiver", "{}").andExpect(status().isOk());

		admin.delete("/api/v1/banques/" + id).andExpect(status().is4xxClientError());
		admin.get("/api/v1/banques/999999").andExpect(status().isNotFound());
		assertThat(jdbc.sql("SELECT count(*) FROM journal_audit WHERE objet_type = 'Banque' AND objet_id = :i")
				.param("i", String.valueOf(id)).query(Long.class).single()).isEqualTo(4);
	}

	@Test
	void lesCentresDeCoutEtLesCategoriesSuiventLesMemesRegles() throws Exception {
		ClientHttp admin = admin();
		String c = code();
		admin.post("/api/v1/centres-cout", "{\"code\":\"%s\",\"libelle\":\"Logistique\"}".formatted(c)).andExpect(status().isCreated());
		admin.post("/api/v1/categories-depense", "{\"code\":\"%s\",\"libelle\":\"Assurance\"}".formatted(c)).andExpect(status().isCreated());
		// Un même code peut exister dans deux référentiels différents, pas deux fois dans le même.
		admin.post("/api/v1/centres-cout", "{\"code\":\"%s\",\"libelle\":\"Autre\"}".formatted(c)).andExpect(status().isUnprocessableEntity());
	}

	@Test
	void lesListesDeChoixSontLisiblesParTousMaisSeulLAdministrateurEcrit() throws Exception {
		ClientHttp boutique = utilisateurAvecRole("boutique");
		boutique.get("/api/v1/banques").andExpect(status().isOk());
		boutique.get("/api/v1/modes-paiement?actif=true").andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(greaterThanOrEqualTo(5)));
		boutique.get("/api/v1/categories-depense").andExpect(status().isOk());
		boutique.post("/api/v1/banques", "{\"code\":\"%s\",\"libelle\":\"X\"}".formatted(code())).andExpect(status().isForbidden());
		boutique.post("/api/v1/banques/1/desactiver", "{}").andExpect(status().isForbidden());
		// Le rôle boutique n'achète pas : il ne voit pas les fournisseurs, ni les paramètres.
		boutique.get("/api/v1/fournisseurs").andExpect(status().isForbidden());
		boutique.get("/api/v1/parametres").andExpect(status().isForbidden());
		boutique.get("/api/v1/tableau-de-bord/administration").andExpect(status().isForbidden());

		utilisateurAvecRole("magasin").get("/api/v1/fournisseurs").andExpect(status().isOk());
	}

	@Test
	void unFournisseurPorteSesContactsEtSaRaisonSocialeEstUnique() throws Exception {
		ClientHttp admin = admin();
		String nom = "Honda Asia " + UUID.randomUUID().toString().substring(0, 6);
		String corps = """
				{"raison_sociale":"%s","pays":"Chine","devise":"usd",
				 "contacts":[{"nom":"Li Wei","fonction":"Export","email":"li@honda.cn","telephone":"+8613000000"},{"nom":"Chen"}]}""";
		admin.post("/api/v1/fournisseurs", corps.formatted(nom)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.devise").value("USD")).andExpect(jsonPath("$.data.contacts.length()").value(2))
				.andExpect(jsonPath("$.data.contacts[0].email").value("li@honda.cn"));
		Long id = jdbc.sql("SELECT id FROM fournisseurs WHERE raison_sociale = :n").param("n", nom).query(Long.class).single();

		admin.post("/api/v1/fournisseurs", corps.formatted(nom.toUpperCase())).andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors.raison_sociale[0]").exists());
		admin.post("/api/v1/fournisseurs", corps.formatted(nom + " bis").replace("\"usd\"", "\"DOLLAR\""))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.devise").exists());
		admin.post("/api/v1/fournisseurs", corps.formatted(nom + " ter").replace("li@honda.cn", "pas-un-mail"))
				.andExpect(status().isUnprocessableEntity());

		// Les contacts se remplacent en bloc.
		admin.put("/api/v1/fournisseurs/" + id, "{\"raison_sociale\":\"%s\",\"pays\":\"Japon\",\"devise\":\"JPY\",\"contacts\":[{\"nom\":\"Sato\"}]}".formatted(nom))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.pays").value("Japon")).andExpect(jsonPath("$.data.contacts.length()").value(1));
		assertThat(jdbc.sql("SELECT count(*) FROM contacts_fournisseur WHERE fournisseur_id = :i").param("i", id).query(Long.class).single()).isEqualTo(1);

		admin.post("/api/v1/fournisseurs/" + id + "/desactiver", "{}").andExpect(status().isOk());
		admin.get("/api/v1/fournisseurs?actif=true&search=" + nom.substring(0, 10)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].id").value(org.hamcrest.Matchers.not(hasItem(id.intValue()))));
	}

	@Test
	void unModeDePaiementSeTraduitVersLaNomenclatureDeLaDgi() throws Exception {
		ClientHttp admin = admin();
		String c = code();
		admin.post("/api/v1/modes-paiement", "{\"code\":\"%s\",\"libelle\":\"Carte\",\"type_dgi\":\"CARTEBANCAIRE\",\"justificatif\":\"Ticket TPE\"}".formatted(c))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.data.type_dgi").value("CARTEBANCAIRE"))
				.andExpect(jsonPath("$.data.systeme").value(false)).andExpect(jsonPath("$.data.justificatif_obligatoire").value(true));
		admin.post("/api/v1/modes-paiement", "{\"code\":\"%s\",\"libelle\":\"X\",\"type_dgi\":\"BITCOIN\"}".formatted(code()))
				.andExpect(status().isBadRequest());
		admin.post("/api/v1/modes-paiement", "{\"code\":\"%s\",\"libelle\":\"X\"}".formatted(code()))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.type_dgi").exists());

		// Un mode personnalisé change de type ; un mode livré avec l'application garde le sien.
		Long id = jdbc.sql("SELECT id FROM modes_paiement WHERE code = :c").param("c", c).query(Long.class).single();
		admin.put("/api/v1/modes-paiement/" + id, "{\"code\":\"%s\",\"libelle\":\"Carte\",\"type_dgi\":\"AUTRE\",\"justificatif_obligatoire\":false}".formatted(c))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.type_dgi").value("AUTRE")).andExpect(jsonPath("$.data.justificatif_obligatoire").value(false));
		Long especes = jdbc.sql("SELECT id FROM modes_paiement WHERE code = 'ESPECES'").query(Long.class).single();
		admin.put("/api/v1/modes-paiement/" + especes, "{\"code\":\"ESPECES\",\"libelle\":\"Espèces\",\"type_dgi\":\"CREDIT\"}")
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.type_dgi[0]").exists());
		admin.put("/api/v1/modes-paiement/" + especes, "{\"code\":\"ESPECES\",\"libelle\":\"Espèces (caisse)\",\"type_dgi\":\"ESPECES\"}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.libelle").value("Espèces (caisse)"));
	}

	@Test
	void leCatalogueDeParametresNInventeAucuneValeur() throws Exception {
		admin().get("/api/v1/parametres").andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].cle").value(hasItem(CatalogueParametres.IFU)))
				.andExpect(jsonPath("$.data[*].cle").value(hasItem("fiscal.taux_taxation_f")))
				.andExpect(jsonPath("$.data[*].cle").value(hasItem(CatalogueParametres.SEUIL_JUSTIFICATIF_DEPENSE)))
				.andExpect(jsonPath("$.data[?(@.cle=='controle.delai_depot_cheque_jours')].renseigne").value(hasItem(false)));
		assertThat(parametres.entier("controle.delai_depot_cheque_jours")).isEmpty();
	}

	@Test
	void uneValeurSeFixeAvecSaDateDEffetEtConserveSonHistorique() throws Exception {
		ClientHttp admin = admin();
		String cle = CatalogueParametres.DELAI_RECEPTION_TRANSFERT_JOURS;
		LocalDate demain = LocalDate.now().plusDays(1);
		LocalDate dansDix = LocalDate.now().plusDays(10);

		admin.put("/api/v1/parametres/" + cle, "{\"valeur\":\"5\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.data[?(@.cle=='%s')].valeur".formatted(cle)).value(hasItem("5")));
		assertThat(parametres.entier(cle)).contains(5L);

		// Une valeur planifiée n'est pas en vigueur : elle apparaît comme « prochaine ».
		admin.put("/api/v1/parametres/" + cle, "{\"valeur\":\"3\",\"date_effet\":\"%s\"}".formatted(dansDix)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data[?(@.cle=='%s')].prochaine.valeur".formatted(cle)).value(hasItem("3")));
		assertThat(parametres.entier(cle)).contains(5L);
		assertThat(parametres.valeurAu(cle, dansDix)).contains("3");
		assertThat(parametres.valeurAu(cle, demain)).contains("5");

		admin.get("/api/v1/parametres/" + cle + "/historique").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(greaterThanOrEqualTo(2)))
				.andExpect(jsonPath("$.data[0].valeur").value("3"));
		assertThat(jdbc.sql("SELECT count(*) FROM journal_audit WHERE action = 'parametre.modifie' AND objet_id = :c")
				.param("c", cle).query(Long.class).single()).isGreaterThanOrEqualTo(2);
	}

	@Test
	void uneValeurInvalideOuAntidateeEstRefusee() throws Exception {
		ClientHttp admin = admin();
		admin.put("/api/v1/parametres/controle.delai_depot_cheque_jours", "{\"valeur\":\"abc\"}")
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.valeur[0]").exists());
		admin.put("/api/v1/parametres/controle.delai_depot_cheque_jours", "{\"valeur\":\"0\"}")
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.valeur[0]").exists());
		admin.put("/api/v1/parametres/fiscal.taux_aib_a", "{\"valeur\":\"120\"}").andExpect(status().isUnprocessableEntity());
		admin.put("/api/v1/parametres/fiscal.jeton_emecef_expiration", "{\"valeur\":\"31/12/2026\"}").andExpect(status().isUnprocessableEntity());
		admin.put("/api/v1/parametres/controle.delai_depot_cheque_jours", "{\"valeur\":\"7\",\"date_effet\":\"2020-01-01\"}")
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.date_effet[0]").exists());
		admin.put("/api/v1/parametres/nexiste.pas", "{\"valeur\":\"1\"}").andExpect(status().isNotFound());
		admin.put("/api/v1/parametres/fiscal.taux_aib_b", "{\"valeur\":\"5,50\"}").andExpect(status().isOk());
		assertThat(parametres.decimal("fiscal.taux_aib_b").orElseThrow()).isEqualByComparingTo("5.5");
	}

	@Test
	void laSyntheseDuTableauDeBordResumeLeParametrageEtSignaleLesAlertes() throws Exception {
		ClientHttp admin = admin();
		admin.put("/api/v1/parametres/fiscal.jeton_emecef_expiration", "{\"valeur\":\"%s\"}".formatted(LocalDate.now().plusDays(10))).andExpect(status().isOk());
		admin.get("/api/v1/tableau-de-bord/administration").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.utilisateurs.total").value(greaterThanOrEqualTo(1)))
				.andExpect(jsonPath("$.data.emplacements.magasins").exists())
				.andExpect(jsonPath("$.data.referentiels.modes_paiement").value(greaterThanOrEqualTo(5)))
				.andExpect(jsonPath("$.data.parametres.fiscaux.total").value(11))
				.andExpect(jsonPath("$.data.parametres.controle.total").value(5))
				.andExpect(jsonPath("$.data.roles").value(greaterThanOrEqualTo(8)))
				.andExpect(jsonPath("$.data.mise_en_service.length()").value(7))
				.andExpect(jsonPath("$.data.alertes[*].message").value(hasItem(org.hamcrest.Matchers.containsString("jeton e-MECeF expire dans"))))
				.andExpect(jsonPath("$.data.activite.length()").value(greaterThanOrEqualTo(1)))
				.andExpect(jsonPath("$.data.activite[0].action").exists());
		// Remis dans un futur lointain pour ne pas perturber les autres tests.
		admin.put("/api/v1/parametres/fiscal.jeton_emecef_expiration", "{\"valeur\":\"%s\"}".formatted(LocalDate.now().plusDays(300))).andExpect(status().isOk());
	}

	@Test
	void lActiviteDuJournalDemandeLeDroitDeLeConsulter() throws Exception {
		// Un rôle personnalisé qui voit l'administration mais pas le journal d'audit.
		ClientHttp admin = admin();
		String role = "role_" + UUID.randomUUID().toString().substring(0, 8);
		admin.post("/api/v1/roles", "{\"code\":\"%s\",\"libelle\":\"Paramétreur\",\"permissions\":[\"administration.voir\"]}".formatted(role)).andExpect(status().isCreated());
		ClientHttp parametreur = utilisateurAvecRole(role);
		parametreur.get("/api/v1/tableau-de-bord/administration").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.utilisateurs").exists()).andExpect(jsonPath("$.data.activite").doesNotExist());
	}

}
