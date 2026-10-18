package bj.vitraco.api.adm;

import bj.vitraco.api.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ReceptionsTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;

	private ComptesDeTest comptes() {
		return new ComptesDeTest(mvc, jdbc);
	}

	private static String unique(String prefixe) {
		return prefixe + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
	}

	private static String numero() {
		return "LWB" + UUID.randomUUID().toString().replace("-", "").substring(0, 9).toUpperCase();
	}

	/**
	 * Un arrivage dédouané, prêt à être reçu : la commande (3 motos A et 2 motos B) est « à réceptionner », le colisage annonce
	 * 3 motos A et 1 moto B (une moto B manque donc au colisage), et deux magasiniers du même magasin sont prêts.
	 */
	private record Decor(Long commande, Long arrivage, Long magasin, Long autreMagasin, String codeA, String codeB, Long modeleA, Long modeleB,
						 String[] numerosA, String numeroB, String premier, String second, String autrePerimetre) {
	}

	private Long emplacement(ClientHttp admin) throws Exception {
		return emplacement(admin, "MAGASIN");
	}

	private Long emplacement(ClientHttp admin, String type) throws Exception {
		String code = unique("E");
		admin.post("/api/v1/emplacements", "{\"code\":\"%s\",\"nom\":\"Lieu %s\",\"type\":\"%s\"%s,\"responsable_nom\":\"R\",\"email\":\"r@vitraco.bj\",\"telephone\":\"1\"}"
				.formatted(code, code, type, type.equals("BOUTIQUE") ? ",\"mode_boutique\":\"A\"" : "")).andExpect(status().isCreated());
		return jdbc.sql("SELECT id FROM emplacements WHERE code = :c").param("c", code).query(Long.class).single();
	}

	private Decor decor() throws Exception {
		ComptesDeTest c = comptes();
		ClientHttp admin = c.admin();
		String nom = "Fournisseur " + unique("");
		admin.post("/api/v1/fournisseurs", "{\"raison_sociale\":\"%s\",\"pays\":\"Chine\",\"devise\":\"USD\",\"contacts\":[]}".formatted(nom)).andExpect(status().isCreated());
		Long fournisseur = jdbc.sql("SELECT id FROM fournisseurs WHERE raison_sociale = :n").param("n", nom).query(Long.class).single();
		String a = unique("MA");
		String b = unique("MB");
		admin.post("/api/v1/modeles", "{\"marque\":\"Honda\",\"modele\":\"A\",\"code_article\":\"%s\",\"format_chassis\":\"^LWB[A-Z0-9]{9}$\",\"prix_vente\":1000000,\"prix_minimum\":900000}".formatted(a)).andExpect(status().isCreated());
		admin.post("/api/v1/modeles", "{\"marque\":\"Honda\",\"modele\":\"B\",\"code_article\":\"%s\",\"prix_vente\":1000000,\"prix_minimum\":900000}".formatted(b)).andExpect(status().isCreated());
		Long ma = jdbc.sql("SELECT id FROM modeles WHERE code_article = :c").param("c", a).query(Long.class).single();
		Long mb = jdbc.sql("SELECT id FROM modeles WHERE code_article = :c").param("c", b).query(Long.class).single();
		String reponse = admin.post("/api/v1/commandes-fournisseur", """
				{"fournisseur_id":%d,"devise":"USD","taux_change":600,"incoterm":"FOB","base_prix":"FOB",
				 "lignes":[{"modele_id":%d,"quantite":3,"prix_unitaire":1000},{"modele_id":%d,"quantite":2,"prix_unitaire":800}]}""".formatted(fournisseur, ma, mb))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		Long commande = Long.valueOf(JsonPath.read(reponse, "$.data.id").toString());
		String cmd = "/api/v1/commandes-fournisseur/" + commande;
		admin.post(cmd + "/avancer", "{\"reference_proforma\":\"PI-1\"}").andExpect(status().isOk());
		String ar = admin.post(cmd + "/arrivages", "{\"numero_bl\":\"%s\",\"date_arrivee_reelle\":\"%s\"}".formatted(unique("BL"), LocalDate.now())).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		Long arrivage = Long.valueOf(JsonPath.read(ar, "$.data.id").toString());
		String[] numerosA = {numero(), numero(), numero()};
		String numeroB = numero();
		admin.postFichier("/api/v1/arrivages/" + arrivage + "/colisage", "c.csv", ("numero_chassis;code_article\n" + numerosA[0] + ";" + a + "\n" + numerosA[1] + ";" + a + "\n"
				+ numerosA[2] + ";" + a + "\n" + numeroB + ";" + b + "\n").getBytes()).andExpect(status().isOk()).andExpect(jsonPath("$.data.nb_importees").value(4));
		admin.post(cmd + "/avancer", "{}").andExpect(status().isOk());
		admin.post(cmd + "/avancer", "{}").andExpect(status().isOk());
		admin.post("/api/v1/arrivages/" + arrivage + "/douane", "{}").andExpect(status().isOk());
		admin.post(cmd + "/avancer", "{}").andExpect(status().isOk());
		admin.post("/api/v1/arrivages/" + arrivage + "/douane", "{}").andExpect(status().isOk());
		admin.post(cmd + "/avancer", "{}").andExpect(status().isOk());

		Long magasin = emplacement(admin);
		Long autre = emplacement(admin);
		return new Decor(commande, arrivage, magasin, autre, a, b, ma, mb, numerosA, numeroB, c.utilisateurActif("magasin", magasin), c.utilisateurActif("magasin", magasin),
				c.utilisateurActif("magasin", autre));
	}

	private Long ouvrir(ClientHttp magasinier, Decor d) throws Exception {
		String reponse = magasinier.post("/api/v1/receptions", "{\"arrivage_id\":%d,\"emplacement_id\":%d}".formatted(d.arrivage(), d.magasin())).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return Long.valueOf(JsonPath.read(reponse, "$.data.id").toString());
	}

	private static String scan(String numero) {
		return "{\"numero\":\"" + numero + "\"}";
	}

	private String statutChassis(String numero) {
		return jdbc.sql("SELECT statut FROM chassis WHERE numero = :n").param("n", numero).query(String.class).single();
	}

	@Test
	void uneReceptionNOuvrePasTantQueLArrivageNEstPasDedouaneNiHorsDuMagasinOuDuPerimetre() throws Exception {
		ComptesDeTest c = comptes();
		Decor d = decor();
		ClientHttp magasinier = c.connecter(d.premier());
		String corps = "{\"arrivage_id\":%d,\"emplacement_id\":%d}";

		// Un arrivage dont le dédouanement n'est pas terminé.
		ClientHttp admin = c.admin();
		String autreCommande = admin.post("/api/v1/commandes-fournisseur/" + d.commande() + "/arrivages", "{\"numero_bl\":\"" + unique("BL") + "\"}").andReturn().getResponse().getContentAsString();
		Long nonDedouane = Long.valueOf(JsonPath.read(autreCommande, "$.data.id").toString());
		magasinier.post("/api/v1/receptions", corps.formatted(nonDedouane, d.magasin())).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("RECEPTION_IMPOSSIBLE"));
		// Le lieu de réception est un magasin actif de son périmètre.
		magasinier.post("/api/v1/receptions", corps.formatted(d.arrivage(), d.autreMagasin())).andExpect(status().isForbidden());
		Long boutique = emplacement(admin, "BOUTIQUE");
		c.connecter(c.utilisateurActif("magasin", boutique)).post("/api/v1/receptions", corps.formatted(d.arrivage(), boutique)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.emplacement_id[0]").exists());
		magasinier.post("/api/v1/receptions", corps.formatted(999999, d.magasin())).andExpect(status().isUnprocessableEntity());

		// Il apparaît chez le magasinier avec ses 4 motos annoncées, puis une seule réception ouverte à la fois.
		magasinier.get("/api/v1/receptions/arrivages-a-receptionner").andExpect(status().isOk())
				.andExpect(jsonPath("$.data[?(@.arrivage_id==%d)].restant".formatted(d.arrivage())).value(hasItem(4)))
				.andExpect(jsonPath("$.data[?(@.arrivage_id==%d)].avec_colisage".formatted(d.arrivage())).value(hasItem(true)));
		magasinier.post("/api/v1/receptions", corps.formatted(d.arrivage(), d.magasin())).andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.numero").value(matchesPattern("^BR-.+-\\d{4}-\\d{6}$"))).andExpect(jsonPath("$.data.statut").value("BROUILLON"))
				.andExpect(jsonPath("$.data.nb_attendus").value(4)).andExpect(jsonPath("$.data.nb_recus").value(0)).andExpect(jsonPath("$.data.validable").value(false))
				.andExpect(jsonPath("$.data.manquants", hasSize(4)));
		c.connecter(d.second()).post("/api/v1/receptions", corps.formatted(d.arrivage(), d.magasin())).andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("RECEPTION_EN_COURS"));
		magasinier.get("/api/v1/receptions/arrivages-a-receptionner").andExpect(jsonPath("$.data[?(@.arrivage_id==%d)].reception_en_cours_numero".formatted(d.arrivage())).exists());

		// Permissions : la boutique et le commercial n'ont pas accès au module.
		c.connecter(c.utilisateurActif("boutique")).get("/api/v1/receptions").andExpect(status().isForbidden());
		c.connecter(c.utilisateurActif("commercial")).post("/api/v1/receptions", corps.formatted(d.arrivage(), d.magasin())).andExpect(status().isForbidden());
		new ClientHttp(mvc).get("/api/v1/receptions").andExpect(status().isUnauthorized());
	}

	@Test
	void chaqueScanEstControleEtLeRapprochementSeMetAJourSansToucherAuStock() throws Exception {
		ComptesDeTest c = comptes();
		Decor d = decor();
		ClientHttp magasinier = c.connecter(d.premier());
		Long id = ouvrir(magasinier, d);
		String url = "/api/v1/receptions/" + id;
		long mouvementsAvant = jdbc.sql("SELECT count(*) FROM mouvements_stock WHERE type = 'RECEPTION'").query(Long.class).single();

		// Moto annoncée : saisie libre (minuscules, tirets), modèle déduit du colisage.
		String brut = (d.numerosA()[0].substring(0, 3) + "-" + d.numerosA()[0].substring(3)).toLowerCase();
		magasinier.post(url + "/chassis", scan(brut)).andExpect(status().isOk()).andExpect(jsonPath("$.data.nb_recus").value(1)).andExpect(jsonPath("$.data.lignes[0].nature").value("ANNONCE"))
				.andExpect(jsonPath("$.data.lignes[0].numero").value(d.numerosA()[0])).andExpect(jsonPath("$.data.manquants", hasSize(3)))
				.andExpect(jsonPath("$.data.resume[?(@.code_article=='%s')].recu".formatted(d.codeA())).value(hasItem(1)))
				.andExpect(jsonPath("$.data.resume[?(@.code_article=='%s')].ecart".formatted(d.codeA())).value(hasItem(-2)));
		// Doublon dans la réception, modèle différent de l'attendu, numéro invalide.
		magasinier.post(url + "/chassis", scan(d.numerosA()[0])).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.numero[0]").value(containsString("déjà scanné")));
		magasinier.post(url + "/chassis", "{\"numero\":\"%s\",\"code_article\":\"%s\"}".formatted(d.numerosA()[1], d.codeB())).andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors.code_article[0]").value(containsString("Modèle différent")));
		magasinier.post(url + "/chassis", scan("@@")).andExpect(status().isUnprocessableEntity());
		// Moto non annoncée : modèle requis, format du modèle respecté, modèle de la commande, et justification exigée (colisage présent).
		String inconnu = numero();
		magasinier.post(url + "/chassis", scan(inconnu)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.code_article[0]").exists());
		magasinier.post(url + "/chassis", "{\"numero\":\"%s\",\"code_article\":\"INCONNU\"}".formatted(inconnu)).andExpect(status().isUnprocessableEntity());
		magasinier.post(url + "/chassis", "{\"numero\":\"XYZ123456789\",\"code_article\":\"%s\",\"motif\":\"x\"}".formatted(d.codeA())).andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors.numero[0]").value(containsString("format")));
		magasinier.post(url + "/chassis", "{\"numero\":\"%s\",\"code_article\":\"%s\"}".formatted(inconnu, d.codeA())).andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors.motif[0]").value(containsString("justifiez")));
		magasinier.post(url + "/chassis", "{\"numero\":\"%s\",\"code_article\":\"%s\",\"motif\":\"Livrée avec le lot, absente du colisage\"}".formatted(inconnu, d.codeA())).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.nb_inattendus").value(1)).andExpect(jsonPath("$.data.lignes[0].nature").value("INATTENDU"));
		// Moto endommagée : le dommage est décrit, elle entrera bloquée.
		magasinier.post(url + "/chassis", "{\"numero\":\"%s\",\"endommage\":true}".formatted(d.numerosA()[1])).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.motif[0]").exists());
		magasinier.post(url + "/chassis", "{\"numero\":\"%s\",\"endommage\":true,\"motif\":\"Réservoir enfoncé\"}".formatted(d.numerosA()[1])).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.nb_bloques").value(1)).andExpect(jsonPath("$.data.nb_recus").value(3)).andExpect(jsonPath("$.data.validable").value(true));
		// Une moto déjà en stock ailleurs ne se rescanne pas.
		Long autre = jdbc.sql("SELECT id FROM chassis WHERE numero = :n").param("n", d.numeroB()).query(Long.class).single();
		jdbc.sql("UPDATE chassis SET statut = 'DISPONIBLE', emplacement_id = :e WHERE id = :i").param("e", d.autreMagasin()).param("i", autre).update();
		magasinier.post(url + "/chassis", scan(d.numeroB())).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.numero[0]").value(containsString("existe déjà")));
		jdbc.sql("UPDATE chassis SET statut = 'A_RECEPTIONNER', emplacement_id = NULL WHERE id = :i").param("i", autre).update();

		// Retirer une ligne remet le compteur à jour ; un brouillon n'a AUCUN effet sur le stock (EF-REC-05).
		Long ligne = Long.valueOf(JsonPath.read(magasinier.get(url).andReturn().getResponse().getContentAsString(), "$.data.lignes[0].id").toString());
		magasinier.delete(url + "/chassis/" + ligne).andExpect(status().isOk()).andExpect(jsonPath("$.data.nb_recus").value(2));
		assertThat(statutChassis(d.numerosA()[0])).isEqualTo("A_RECEPTIONNER");
		assertThat(statutChassis(d.numerosA()[1])).isEqualTo("A_RECEPTIONNER");
		assertThat(jdbc.sql("SELECT count(*) FROM mouvements_stock WHERE type = 'RECEPTION'").query(Long.class).single()).isEqualTo(mouvementsAvant);
		assertThat(jdbc.sql("SELECT statut FROM commandes_fournisseur WHERE id = :c").param("c", d.commande()).query(String.class).single()).isEqualTo("A_RECEPTIONNER");
		assertThat(jdbc.sql("SELECT sum(quantite_recue) FROM lignes_commande_fournisseur WHERE commande_id = :c").param("c", d.commande()).query(Long.class).single()).isZero();
	}

	@Test
	void laValidationParUnSecondUtilisateurFaitEntrerLesMotosEnStockEtMetLaCommandeAJour() throws Exception {
		ComptesDeTest c = comptes();
		Decor d = decor();
		ClientHttp premier = c.connecter(d.premier());
		ClientHttp second = c.connecter(d.second());
		Long id = ouvrir(premier, d);
		String url = "/api/v1/receptions/" + id;
		String inconnu = numero();

		second.post(url + "/valider", "{}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("RECEPTION_VIDE"));
		premier.post(url + "/chassis", scan(d.numerosA()[0])).andExpect(status().isOk());
		premier.post(url + "/chassis", "{\"numero\":\"%s\",\"endommage\":true,\"motif\":\"Phare cassé\"}".formatted(d.numerosA()[1])).andExpect(status().isOk());
		premier.post(url + "/chassis", "{\"numero\":\"%s\",\"code_article\":\"%s\",\"motif\":\"Hors colisage\"}".formatted(inconnu, d.codeA())).andExpect(status().isOk());
		String email = c.utilisateurActif("controleur");
		long avant = jdbc.sql("SELECT count(*) FROM notifications WHERE destinataire_id = :u AND type = 'reception.ecart'").param("u", c.id(email)).query(Long.class).single();

		// L'ouvreur ne valide pas sa propre réception.
		premier.post(url + "/valider", "{}").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("UTILISATEUR_IDENTIQUE"));
		second.post(url + "/valider", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.data.statut").value("VALIDEE")).andExpect(jsonPath("$.data.valide_par").exists())
				.andExpect(jsonPath("$.data.nb_recus").value(3)).andExpect(jsonPath("$.data.manquants", hasSize(2)));

		// Les motos sont en stock dans le magasin : disponible, bloquée (endommagée), et la non annoncée créée.
		assertThat(statutChassis(d.numerosA()[0])).isEqualTo("DISPONIBLE");
		assertThat(statutChassis(d.numerosA()[1])).isEqualTo("BLOQUEE");
		assertThat(statutChassis(inconnu)).isEqualTo("DISPONIBLE");
		assertThat(jdbc.sql("SELECT count(*) FROM chassis WHERE reception_id = :r AND emplacement_id = :e AND date_entree = current_date").param("r", id).param("e", d.magasin()).query(Long.class).single()).isEqualTo(3);
		assertThat(jdbc.sql("SELECT motif FROM mouvements_stock m JOIN chassis c ON c.id = m.chassis_id WHERE c.numero = :n AND m.type = 'RECEPTION'").param("n", d.numerosA()[1]).query(String.class).single()).isEqualTo("Phare cassé");
		assertThat(jdbc.sql("SELECT count(*) FROM mouvements_stock m JOIN chassis c ON c.id = m.chassis_id WHERE c.reception_id = :r AND m.document_type = 'RECEPTION' AND m.document_id = :r").param("r", id).query(Long.class).single()).isEqualTo(3);
		// La moto manquante au colisage reste à réceptionner : elle se recevra plus tard.
		assertThat(statutChassis(d.numerosA()[2])).isEqualTo("A_RECEPTIONNER");
		assertThat(statutChassis(d.numeroB())).isEqualTo("A_RECEPTIONNER");

		// La commande passe « Réception partielle » avec ses quantités reçues par modèle ; le contrôle est prévenu de l'écart.
		assertThat(jdbc.sql("SELECT statut FROM commandes_fournisseur WHERE id = :c").param("c", d.commande()).query(String.class).single()).isEqualTo("RECEPTION_PARTIELLE");
		assertThat(jdbc.sql("SELECT quantite_recue FROM lignes_commande_fournisseur WHERE commande_id = :c AND modele_id = :m").param("c", d.commande()).param("m", d.modeleA()).query(Integer.class).single()).isEqualTo(3);
		assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE destinataire_id = :u AND type = 'reception.ecart'").param("u", c.id(email)).query(Long.class).single()).isEqualTo(avant + 1);
		assertThat(jdbc.sql("SELECT count(*) FROM journal_audit WHERE action = 'reception.validee' AND objet_id = :i").param("i", String.valueOf(id)).query(Long.class).single()).isEqualTo(1);

		// Validée, elle ne bouge plus ; le PV est disponible.
		premier.post(url + "/chassis", scan(d.numeroB())).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("RECEPTION_VALIDEE"));
		second.post(url + "/valider", "{}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("RECEPTION_VALIDEE"));
		byte[] pdf = second.get(url + "/pv").andExpect(status().isOk()).andExpect(content().contentType("application/pdf")).andReturn().getResponse().getContentAsByteArray();
		assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
		assertThat(pdf.length).isGreaterThan(1000);

		// Seconde réception du reliquat : la commande est « Réceptionnée » quand tout est là.
		Long suite = ouvrir(premier, d);
		premier.post("/api/v1/receptions/" + suite + "/chassis", scan(d.numerosA()[2])).andExpect(status().isOk()).andExpect(jsonPath("$.data.nb_attendus").value(2));
		premier.post("/api/v1/receptions/" + suite + "/chassis", scan(d.numeroB())).andExpect(status().isOk());
		second.post("/api/v1/receptions/" + suite + "/valider", "{}").andExpect(status().isOk());
		assertThat(jdbc.sql("SELECT statut FROM commandes_fournisseur WHERE id = :c").param("c", d.commande()).query(String.class).single()).isEqualTo("RECEPTION_PARTIELLE");
		// Il manque encore 1 moto B (2 commandées, 1 reçue) : un surplus sur A n'y change rien.
		assertThat(jdbc.sql("SELECT quantite_recue FROM lignes_commande_fournisseur WHERE commande_id = :c AND modele_id = :m").param("c", d.commande()).param("m", d.modeleB()).query(Integer.class).single()).isEqualTo(1);
	}

	@Test
	void lePvEstRefuseTantQueLaReceptionNEstPasValidee() throws Exception {
		ComptesDeTest c = comptes();
		Decor d = decor();
		ClientHttp premier = c.connecter(d.premier());
		Long id = ouvrir(premier, d);
		premier.get("/api/v1/receptions/" + id + "/pv").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("RECEPTION_NON_VALIDEE"));
	}

	@Test
	void laSeparationDesTachesSeDesactiveParParametreEtSeRetablitParDefaut() throws Exception {
		ComptesDeTest c = comptes();
		ClientHttp admin = c.admin();
		Decor d = decor();
		ClientHttp premier = c.connecter(d.premier());
		Long id = ouvrir(premier, d);
		premier.post("/api/v1/receptions/" + id + "/chassis", scan(d.numerosA()[0])).andExpect(status().isOk());

		admin.get("/api/v1/parametres").andExpect(jsonPath("$.data[?(@.cle=='controle.reception_utilisateurs_differents')].defaut").value(hasItem("true")))
				.andExpect(jsonPath("$.data[?(@.cle=='controle.reception_utilisateurs_differents')].type").value(hasItem("BOOLEEN")));
		admin.put("/api/v1/parametres/controle.reception_utilisateurs_differents", "{\"valeur\":\"NON\"}").andExpect(status().isOk());
		try {
			premier.post("/api/v1/receptions/" + id + "/valider", "{}").andExpect(status().isOk());
		} finally {
			admin.put("/api/v1/parametres/controle.reception_utilisateurs_differents", "{\"valeur\":\"oui\"}").andExpect(status().isOk());
		}
		admin.put("/api/v1/parametres/controle.reception_utilisateurs_differents", "{\"valeur\":\"peut-être\"}").andExpect(status().isUnprocessableEntity());
	}

	@Test
	void chaqueMagasinNeVoitEtNeTraiteQueSesPropresReceptions() throws Exception {
		ComptesDeTest c = comptes();
		Decor d = decor();
		ClientHttp premier = c.connecter(d.premier());
		Long id = ouvrir(premier, d);
		ClientHttp ailleurs = c.connecter(d.autrePerimetre());

		ailleurs.get("/api/v1/receptions/" + id).andExpect(status().isNotFound());
		ailleurs.post("/api/v1/receptions/" + id + "/chassis", scan(d.numerosA()[0])).andExpect(status().isNotFound());
		ailleurs.post("/api/v1/receptions/" + id + "/valider", "{}").andExpect(status().isNotFound());
		ailleurs.get("/api/v1/receptions?arrivage_id=" + d.arrivage()).andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(0)));
		premier.get("/api/v1/receptions?arrivage_id=" + d.arrivage()).andExpect(jsonPath("$.data", hasSize(1))).andExpect(jsonPath("$.data[0].statut").value("BROUILLON"));
		premier.get("/api/v1/receptions?search=" + d.codeA().substring(0, 4)).andExpect(status().isOk());
		c.admin().get("/api/v1/receptions/" + id).andExpect(status().isOk());
	}

}
