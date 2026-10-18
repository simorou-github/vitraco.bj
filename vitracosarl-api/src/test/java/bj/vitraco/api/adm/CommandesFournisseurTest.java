package bj.vitraco.api.adm;

import bj.vitraco.api.TestcontainersConfiguration;
import bj.vitraco.api.TestcontainersConfiguration.ConditionsSimulees;
import bj.vitraco.api.app.CommandeFournisseurService;
import bj.vitraco.api.app.StatutCommande;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CommandesFournisseurTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	CommandeFournisseurService service;
	@Autowired
	TransactionTemplate transaction;
	@Autowired
	ConditionsSimulees conditions;

	/** Enregistre une réception dans sa propre transaction, comme le fera le module REC. */
	private StatutCommande reception(Long commande, Long modele, int quantite) {
		return transaction.execute(s -> service.enregistrerReception(commande, modele, quantite));
	}

	private ComptesDeTest comptes() {
		return new ComptesDeTest(mvc, jdbc);
	}

	private static String unique(String prefixe) {
		return prefixe + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
	}

	private record Decor(Long fournisseur, String nomFournisseur, Long modeleA, Long modeleB, String codeA) {
	}

	private Decor decor() throws Exception {
		ClientHttp admin = comptes().admin();
		String nom = "Honda Asia " + unique("");
		admin.post("/api/v1/fournisseurs", "{\"raison_sociale\":\"%s\",\"pays\":\"Chine\",\"devise\":\"USD\",\"contacts\":[]}".formatted(nom)).andExpect(status().isCreated());
		Long fournisseur = jdbc.sql("SELECT id FROM fournisseurs WHERE raison_sociale = :n").param("n", nom).query(Long.class).single();
		String a = unique("MA");
		String b = unique("MB");
		for (String code : new String[]{a, b}) {
			admin.post("/api/v1/modeles", "{\"marque\":\"Honda\",\"modele\":\"%s\",\"code_article\":\"%s\",\"prix_vente\":1000000,\"prix_minimum\":900000}".formatted(code, code))
					.andExpect(status().isCreated());
		}
		return new Decor(fournisseur, nom, idModele(a), idModele(b), a);
	}

	private Long idModele(String code) {
		return jdbc.sql("SELECT id FROM modeles WHERE code_article = :c").param("c", code).query(Long.class).single();
	}

	private static String corps(Decor d, String devise, String taux) {
		return """
				{"fournisseur_id":%d,"devise":"%s",%s"incoterm":"FOB","base_prix":"FOB","observation":"Lot de saison",
				 "lignes":[{"modele_id":%d,"quantite":10,"prix_unitaire":1000.50},{"modele_id":%d,"quantite":5,"prix_unitaire":800}]}"""
				.formatted(d.fournisseur(), devise, taux == null ? "" : "\"taux_change\":" + taux + ",", d.modeleA(), d.modeleB());
	}

	/** Un arrivage déjà arrivé au port : de quoi satisfaire les conditions « en transit » et « au port ». */
	private Long arrivage(ClientHttp acheteur, Long commandeId) throws Exception {
		String reponse = acheteur.post("/api/v1/commandes-fournisseur/" + commandeId + "/arrivages",
				"{\"numero_bl\":\"%s\",\"date_arrivee_reelle\":\"%s\"}".formatted(unique("BL"), java.time.LocalDate.now())).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return Long.valueOf(JsonPath.read(reponse, "$.data.id").toString());
	}

	private Long creer(ClientHttp acheteur, Decor d) throws Exception {
		String reponse = acheteur.post("/api/v1/commandes-fournisseur", corps(d, "USD", "600")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return Long.valueOf(JsonPath.read(reponse, "$.data.id").toString());
	}

	@Test
	void uneCommandeSeCreeNumeroteeAvecSesTotauxEtNeTouchePasAuStock() throws Exception {
		Decor d = decor();
		ClientHttp admin = comptes().admin();
		String reponse = admin.post("/api/v1/commandes-fournisseur", corps(d, "usd", "600")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.numero").value(matchesPattern("^PO-\\d{4}-\\d{6}$"))).andExpect(jsonPath("$.data.statut").value("COMMANDEE"))
				.andExpect(jsonPath("$.data.devise").value("USD")).andExpect(jsonPath("$.data.fournisseur.raison_sociale").value(d.nomFournisseur()))
				.andExpect(jsonPath("$.data.lignes", hasSize(2))).andExpect(jsonPath("$.data.total_commande").value(15)).andExpect(jsonPath("$.data.total_recu").value(0))
				// 10 × 1 000,50 USD = 10 005 USD → 6 003 000 FCFA ; 5 × 800 USD = 4 000 USD → 2 400 000 FCFA.
				.andExpect(jsonPath("$.data.total_devise").value(14005.0)).andExpect(jsonPath("$.data.total_fcfa").value(8_403_000))
				.andExpect(jsonPath("$.data.prochain_statut").value("CONFIRMEE")).andExpect(jsonPath("$.data.modifiable").value(true))
				.andExpect(jsonPath("$.data.historique", hasSize(1))).andExpect(jsonPath("$.data.historique[0].type").value("CREATION"))
				.andReturn().getResponse().getContentAsString();
		Long id = Long.valueOf(JsonPath.read(reponse, "$.data.id").toString());

		assertThat(jdbc.sql("SELECT count(*) FROM chassis").query(Long.class).single()).isEqualTo(jdbc.sql("SELECT count(*) FROM chassis").query(Long.class).single());
		assertThat(jdbc.sql("SELECT count(*) FROM journal_audit WHERE action = 'commande_fournisseur.creee' AND objet_id = :i").param("i", String.valueOf(id))
				.query(Long.class).single()).isEqualTo(1);
		// Deux commandes successives ont deux numéros distincts, sans trou.
		String autre = admin.post("/api/v1/commandes-fournisseur", corps(d, "USD", "600")).andReturn().getResponse().getContentAsString();
		long n1 = Long.parseLong(JsonPath.<String>read(reponse, "$.data.numero").substring(8));
		long n2 = Long.parseLong(JsonPath.<String>read(autre, "$.data.numero").substring(8));
		assertThat(n2).isEqualTo(n1 + 1);
	}

	@Test
	void lesPrixSontMasquesAuMagasinQuiVoitSeulementLesQuantites() throws Exception {
		Decor d = decor();
		ComptesDeTest c = comptes();
		Long id = creer(c.admin(), d);
		ClientHttp magasin = c.connecter(c.utilisateurActif("magasin"));

		magasin.get("/api/v1/commandes-fournisseur/" + id).andExpect(status().isOk()).andExpect(jsonPath("$.data.prix_visibles").value(false))
				.andExpect(jsonPath("$.data.lignes[0].prix_unitaire").doesNotExist()).andExpect(jsonPath("$.data.total_fcfa").doesNotExist())
				.andExpect(jsonPath("$.data.taux_change").doesNotExist()).andExpect(jsonPath("$.data.lignes[0].quantite").exists())
				.andExpect(jsonPath("$.data.lignes[0].reliquat").exists());
		magasin.get("/api/v1/commandes-fournisseur?search=" + d.nomFournisseur()).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].total_fcfa").doesNotExist());
		magasin.post("/api/v1/commandes-fournisseur", corps(d, "USD", "600")).andExpect(status().isForbidden());
		magasin.post("/api/v1/commandes-fournisseur/" + id + "/avancer", "{}").andExpect(status().isForbidden());
		new ClientHttp(mvc).get("/api/v1/commandes-fournisseur").andExpect(status().isUnauthorized());
		// La Direction a la permission « prix d'achat » : elle voit les montants sans pouvoir saisir.
		ClientHttp direction = c.connecter(c.utilisateurActif("direction"));
		direction.get("/api/v1/commandes-fournisseur/" + id).andExpect(jsonPath("$.data.total_fcfa").value(8_403_000));
	}

	@Test
	void leFournisseurLaDeviseLeTauxEtLesLignesSontControles() throws Exception {
		Decor d = decor();
		ClientHttp admin = comptes().admin();
		String url = "/api/v1/commandes-fournisseur";

		admin.post(url, corps(d, "USD", null)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.taux_change[0]").exists());
		admin.post(url, corps(d, "XOF", "2")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.taux_change[0]").exists());
		admin.post(url, corps(d, "XOF", null)).andExpect(status().isCreated()).andExpect(jsonPath("$.data.taux_change").value(1))
				.andExpect(jsonPath("$.data.total_fcfa").value(14_005));
		admin.post(url, corps(d, "DOLLAR", "600")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.devise").exists());
		admin.post(url, corps(d, "USD", "600").replace("\"FOB\",\"base_prix\"", "\"XYZ\",\"base_prix\"")).andExpect(status().isBadRequest());
		admin.post(url, corps(d, "USD", "600").replace("\"fournisseur_id\":" + d.fournisseur(), "\"fournisseur_id\":999999")).andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors.fournisseur_id[0]").exists());
		admin.post(url, "{\"fournisseur_id\":%d,\"devise\":\"USD\",\"taux_change\":600,\"incoterm\":\"FOB\",\"base_prix\":\"FOB\",\"lignes\":[]}".formatted(d.fournisseur()))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.lignes").exists());
		// Un modèle n'apparaît qu'une fois ; une quantité nulle ou un prix négatif sont refusés.
		admin.post(url, corps(d, "USD", "600").replace("\"modele_id\":" + d.modeleB(), "\"modele_id\":" + d.modeleA())).andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors.lignes[0]").exists());
		admin.post(url, corps(d, "USD", "600").replace("\"quantite\":10", "\"quantite\":0")).andExpect(status().isUnprocessableEntity());
		admin.post(url, corps(d, "USD", "600").replace("\"prix_unitaire\":800", "\"prix_unitaire\":-1")).andExpect(status().isUnprocessableEntity());

		// Un fournisseur ou un modèle désactivés ne se commandent plus.
		admin.post("/api/v1/modeles/" + d.modeleA() + "/desactiver", "{}").andExpect(status().isOk());
		admin.post(url, corps(d, "USD", "600")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.lignes[0]").exists());
		admin.post("/api/v1/modeles/" + d.modeleA() + "/reactiver", "{}").andExpect(status().isOk());
		admin.post("/api/v1/fournisseurs/" + d.fournisseur() + "/desactiver", "{}").andExpect(status().isOk());
		admin.post(url, corps(d, "USD", "600")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.fournisseur_id[0]").exists());
	}

	@Test
	void uneCommandeSeModifieTantQueLeFournisseurNAPasConfirme() throws Exception {
		Decor d = decor();
		ClientHttp admin = comptes().admin();
		Long id = creer(admin, d);

		admin.put("/api/v1/commandes-fournisseur/" + id, """
				{"fournisseur_id":%d,"devise":"EUR","taux_change":655.957,"incoterm":"EXW","base_prix":"EXW",
				 "lignes":[{"modele_id":%d,"quantite":3,"prix_unitaire":1500}]}""".formatted(d.fournisseur(), d.modeleA()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.devise").value("EUR")).andExpect(jsonPath("$.data.lignes", hasSize(1)))
				.andExpect(jsonPath("$.data.total_commande").value(3)).andExpect(jsonPath("$.data.total_fcfa").value(2_951_807));
		assertThat(jdbc.sql("SELECT count(*) FROM lignes_commande_fournisseur WHERE commande_id = :c").param("c", id).query(Long.class).single()).isEqualTo(1);

		admin.post("/api/v1/commandes-fournisseur/" + id + "/avancer", "{\"reference_proforma\":\"PI-001\"}").andExpect(status().isOk());
		admin.put("/api/v1/commandes-fournisseur/" + id, corps(d, "USD", "600")).andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("COMMANDE_NON_MODIFIABLE"));
		admin.get("/api/v1/commandes-fournisseur/999999").andExpect(status().isNotFound());
	}

	@Test
	void leWorkflowAvanceEtapeParEtapeAvecDateEtAuteurEtNeSautePasDEtape() throws Exception {
		Decor d = decor();
		ComptesDeTest c = comptes();
		ClientHttp admin = c.admin();
		Long id = creer(admin, d);
		String url = "/api/v1/commandes-fournisseur/" + id;
		String email = c.utilisateurActif("magasin");
		long avant = jdbc.sql("SELECT count(*) FROM notifications WHERE destinataire_id = :u AND type = 'commande_fournisseur.a_receptionner'")
				.param("u", c.id(email)).query(Long.class).single();

		// La confirmation exige la référence de la proforma ; une étape ne se saute pas.
		admin.post(url + "/avancer", "{}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.reference_proforma[0]").exists());
		admin.post(url + "/avancer", "{\"cible\":\"AU_PORT\",\"reference_proforma\":\"PI-2026-17\"}").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("ETAPE_SAUTEE"));
		admin.post(url + "/avancer", "{\"cible\":\"CONFIRMEE\",\"reference_proforma\":\"PI-2026-17\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.statut").value("CONFIRMEE")).andExpect(jsonPath("$.data.reference_proforma").value("PI-2026-17"))
				.andExpect(jsonPath("$.data.prochain_statut").value("EN_TRANSIT")).andExpect(jsonPath("$.data.modifiable").value(false));
		// Les arrivages commandent les étapes : pas de transit sans arrivage, pas de dédouanement avant son démarrage.
		admin.post(url + "/avancer", "{}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ETAPE_REFUSEE"));
		Long arrivage = arrivage(admin, id);
		for (String attendu : new String[]{"EN_TRANSIT", "AU_PORT", "DEDOUANEMENT", "A_RECEPTIONNER"}) {
			if (attendu.equals("DEDOUANEMENT")) {
				admin.post("/api/v1/arrivages/" + arrivage + "/douane", "{}").andExpect(status().isOk());
			}
			if (attendu.equals("A_RECEPTIONNER")) {
				admin.post("/api/v1/arrivages/" + arrivage + "/douane", "{}").andExpect(status().isOk());
			}
			admin.post(url + "/avancer", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.data.statut").value(attendu));
		}
		// Les statuts de réception se posent seuls, à la validation des réceptions.
		admin.post(url + "/avancer", "{}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ETAPE_AUTOMATIQUE"));

		admin.get(url).andExpect(jsonPath("$.data.historique", hasSize(6))).andExpect(jsonPath("$.data.historique[5].statut_avant").value("DEDOUANEMENT"))
				.andExpect(jsonPath("$.data.historique[5].statut_apres").value("A_RECEPTIONNER")).andExpect(jsonPath("$.data.historique[5].auteur").value("Administrateur"))
				.andExpect(jsonPath("$.data.historique[5].date").exists());
		// Le magasin est prévenu quand la marchandise l'attend.
		assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE destinataire_id = :u AND type = 'commande_fournisseur.a_receptionner'")
				.param("u", c.id(email)).query(Long.class).single()).isEqualTo(avant + 1);
	}

	@Test
	void uneConditionDUnAutreModuleBloqueLePassageDEtape() throws Exception {
		Decor d = decor();
		ClientHttp admin = comptes().admin();
		Long id = creer(admin, d);
		admin.post("/api/v1/commandes-fournisseur/" + id + "/avancer", "{\"reference_proforma\":\"PI-1\"}").andExpect(status().isOk());

		conditions.bloquer(id, StatutCommande.EN_TRANSIT);
		admin.post("/api/v1/commandes-fournisseur/" + id + "/avancer", "{}").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("ETAPE_REFUSEE")).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("aucun arrivage")));
		assertThat(jdbc.sql("SELECT statut FROM commandes_fournisseur WHERE id = :i").param("i", id).query(String.class).single()).isEqualTo("CONFIRMEE");
	}

	@Test
	void unRetourArriereExigeUnDroitSpecifiqueEtUnMotif() throws Exception {
		Decor d = decor();
		ComptesDeTest c = comptes();
		ClientHttp admin = c.admin();
		Long id = creer(admin, d);
		String url = "/api/v1/commandes-fournisseur/" + id;
		admin.post(url + "/avancer", "{\"reference_proforma\":\"PI-1\"}").andExpect(status().isOk());
		arrivage(admin, id);
		admin.post(url + "/avancer", "{}").andExpect(status().isOk());

		// L'acheteur n'a pas ce droit ; la Direction l'a, avec un motif.
		admin.post(url + "/reculer", "{\"motif\":\"Erreur de saisie\"}").andExpect(status().isForbidden());
		ClientHttp direction = c.connecter(c.utilisateurActif("direction"));
		direction.post(url + "/reculer", "{}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.motif").exists());
		direction.post(url + "/reculer", "{\"motif\":\"Le navire n'est pas parti\"}").andExpect(status().isOk()).andExpect(jsonPath("$.data.statut").value("CONFIRMEE"))
				.andExpect(jsonPath("$.data.historique[3].type").value("RETOUR_ARRIERE")).andExpect(jsonPath("$.data.historique[3].motif").value("Le navire n'est pas parti"));
		direction.post(url + "/reculer", "{\"motif\":\"Encore\"}").andExpect(status().isOk()).andExpect(jsonPath("$.data.statut").value("COMMANDEE"));
		direction.post(url + "/reculer", "{\"motif\":\"Encore\"}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("RETOUR_IMPOSSIBLE"));
		assertThat(jdbc.sql("SELECT count(*) FROM journal_audit WHERE action = 'commande_fournisseur.retour_arriere' AND objet_id = :i").param("i", String.valueOf(id))
				.query(Long.class).single()).isEqualTo(2);
	}

	@Test
	void uneCommandeSAnnuleAvantLeDepartAvecUnMotifEtSeFigeEnsuite() throws Exception {
		Decor d = decor();
		ComptesDeTest c = comptes();
		ClientHttp admin = c.admin();
		ClientHttp direction = c.connecter(c.utilisateurActif("direction"));
		Long id = creer(admin, d);
		String url = "/api/v1/commandes-fournisseur/" + id;
		admin.post(url + "/avancer", "{\"reference_proforma\":\"PI-1\"}").andExpect(status().isOk());

		admin.post(url + "/annuler", "{\"motif\":\"x\"}").andExpect(status().isForbidden());
		direction.post(url + "/annuler", "{}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.motif").exists());
		direction.post(url + "/annuler", "{\"motif\":\"Fournisseur en rupture\"}").andExpect(status().isOk()).andExpect(jsonPath("$.data.statut").value("ANNULEE"))
				.andExpect(jsonPath("$.data.annulable").value(false)).andExpect(jsonPath("$.data.prochain_statut").doesNotExist());
		admin.post(url + "/avancer", "{}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("COMMANDE_ANNULEE"));
		direction.post(url + "/annuler", "{\"motif\":\"Encore\"}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ANNULATION_IMPOSSIBLE"));

		// Une fois la marchandise partie, on n'annule plus.
		Long enRoute = creer(admin, d);
		admin.post("/api/v1/commandes-fournisseur/" + enRoute + "/avancer", "{\"reference_proforma\":\"PI-2\"}").andExpect(status().isOk());
		arrivage(admin, enRoute);
		admin.post("/api/v1/commandes-fournisseur/" + enRoute + "/avancer", "{}").andExpect(status().isOk());
		direction.post("/api/v1/commandes-fournisseur/" + enRoute + "/annuler", "{\"motif\":\"Trop tard\"}").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("ANNULATION_IMPOSSIBLE"));
		// Rien ne se supprime.
		direction.delete(url).andExpect(status().is4xxClientError());
		assertThat(jdbc.sql("SELECT count(*) FROM commandes_fournisseur WHERE id = :i").param("i", id).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void lesReceptionsFontPasserLaCommandeEnReceptionPartielleOuReceptionneeSansToucherAuStock() throws Exception {
		Decor d = decor();
		ClientHttp admin = comptes().admin();
		Long id = creer(admin, d);
		long chassisAvant = jdbc.sql("SELECT count(*) FROM chassis").query(Long.class).single();

		assertThatThrownBy(() -> transaction.executeWithoutResult(s -> service.enregistrerReception(id, d.modeleA(), 1)))
				.hasMessageContaining("ne peut pas recevoir");
		jdbc.sql("UPDATE commandes_fournisseur SET statut = 'A_RECEPTIONNER' WHERE id = :i").param("i", id).update();

		assertThat(reception(id, d.modeleA(), 4)).isEqualTo(StatutCommande.RECEPTION_PARTIELLE);
		admin.get("/api/v1/commandes-fournisseur/" + id).andExpect(jsonPath("$.data.statut").value("RECEPTION_PARTIELLE")).andExpect(jsonPath("$.data.total_recu").value(4))
				.andExpect(jsonPath("$.data.lignes[?(@.modele_id==%d)].reliquat".formatted(d.modeleA())).value(hasItem(6)))
				.andExpect(jsonPath("$.data.lignes[?(@.modele_id==%d)].reliquat".formatted(d.modeleB())).value(hasItem(5)));
		assertThatThrownBy(() -> transaction.executeWithoutResult(s -> service.enregistrerReception(id, 999_999L, 1))).hasMessageContaining("ne figure pas");
		assertThat(reception(id, d.modeleA(), 6)).isEqualTo(StatutCommande.RECEPTION_PARTIELLE);
		// Un surplus accepté dépasse la quantité commandée : le reliquat devient négatif.
		assertThat(reception(id, d.modeleB(), 6)).isEqualTo(StatutCommande.RECEPTIONNEE);
		admin.get("/api/v1/commandes-fournisseur/" + id).andExpect(jsonPath("$.data.lignes[?(@.modele_id==%d)].reliquat".formatted(d.modeleB())).value(hasItem(-1)));

		assertThat(jdbc.sql("SELECT count(*) FROM chassis").query(Long.class).single()).isEqualTo(chassisAvant);
		// L'historique est en ajout seul, même en SQL direct.
		assertThatThrownBy(() -> jdbc.sql("UPDATE historique_commande_fournisseur SET motif = 'x' WHERE commande_id = :c").param("c", id).update())
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("DELETE FROM historique_commande_fournisseur WHERE commande_id = :c").param("c", id).update())
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void laListeSeFiltreParStatutFournisseurRechercheEtDates() throws Exception {
		Decor d = decor();
		ClientHttp admin = comptes().admin();
		Long a = creer(admin, d);
		Long b = creer(admin, d);
		admin.post("/api/v1/commandes-fournisseur/" + b + "/avancer", "{\"reference_proforma\":\"PROFORMA-UNIQUE-77\"}").andExpect(status().isOk());
		String f = "fournisseur_id=" + d.fournisseur();

		admin.get("/api/v1/commandes-fournisseur?" + f).andExpect(status().isOk()).andExpect(jsonPath("$.meta.total").value(2)).andExpect(jsonPath("$.data[0].id").value(b.intValue()));
		admin.get("/api/v1/commandes-fournisseur?" + f + "&statut=CONFIRMEE").andExpect(jsonPath("$.data", hasSize(1))).andExpect(jsonPath("$.data[0].id").value(b.intValue()));
		admin.get("/api/v1/commandes-fournisseur?search=proforma-unique").andExpect(jsonPath("$.data", hasSize(1)));
		admin.get("/api/v1/commandes-fournisseur?search=" + d.nomFournisseur().toLowerCase()).andExpect(jsonPath("$.data", hasSize(2)));
		admin.get("/api/v1/commandes-fournisseur?" + f + "&depuis=2999-01-01").andExpect(jsonPath("$.data", hasSize(0)));
		admin.get("/api/v1/commandes-fournisseur?" + f + "&jusqua=2000-01-01").andExpect(jsonPath("$.data", hasSize(0)));
		admin.get("/api/v1/commandes-fournisseur?statut=INCONNU").andExpect(status().isBadRequest());
		assertThat(a).isNotEqualTo(b);
	}

}
