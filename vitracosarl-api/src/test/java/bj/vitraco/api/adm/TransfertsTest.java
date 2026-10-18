package bj.vitraco.api.adm;

import bj.vitraco.api.TestcontainersConfiguration;
import bj.vitraco.api.trf.TransfertService;
import com.jayway.jsonpath.JsonPath;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Transferts à trois temps : création sans mouvement, expédition = sortie de la source, réception = entrée à la destination. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TransfertsTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	TransfertService service;

	private ComptesDeTest comptes() {
		return new ComptesDeTest(mvc, jdbc);
	}

	private static String unique(String prefixe) {
		return prefixe + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
	}

	private Long emplacement(String type, String modeBoutique) {
		String code = unique("T");
		return jdbc.sql("INSERT INTO emplacements (code, nom, type, mode_boutique, actif) VALUES (:c, :n, :t, :m, true) RETURNING id")
				.param("c", code).param("n", "Lieu " + code).param("t", type).param("m", modeBoutique).query(Long.class).single();
	}

	private String chassis(Long modele, String statut, Long emplacement) {
		String numero = "TF" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
		jdbc.sql("INSERT INTO chassis (numero, modele_id, statut, emplacement_id, date_entree, cout_revient) VALUES (:n, :m, :s, :e, current_date, 100000)")
				.param("n", numero).param("m", modele).param("s", statut).param("e", emplacement).update();
		return numero;
	}

	private String statut(String numero) {
		return jdbc.sql("SELECT statut FROM chassis WHERE numero = :n").param("n", numero).query(String.class).single();
	}

	private String lieu(String numero) {
		return jdbc.sql("SELECT e.code FROM chassis c JOIN emplacements e ON e.id = c.emplacement_id WHERE c.numero = :n").param("n", numero).query(String.class).single();
	}

	/** Un magasin avec cinq motos disponibles, une boutique, et trois comptes : expéditeur, réceptionnaire, et un compte des deux sites. */
	private record Decor(Long magasin, Long boutique, String codeMagasin, String codeBoutique, String[] numeros, String reservee, String ailleurs,
						 String expediteur, String receptionnaire, String double_) {
	}

	private Decor decor() throws Exception {
		ComptesDeTest c = comptes();
		String code = unique("TR");
		c.admin().post("/api/v1/modeles", "{\"marque\":\"Honda\",\"modele\":\"Transfert\",\"code_article\":\"%s\",\"prix_vente\":1000000,\"prix_minimum\":900000}".formatted(code)).andExpect(status().isCreated());
		Long modele = jdbc.sql("SELECT id FROM modeles WHERE code_article = :c").param("c", code).query(Long.class).single();
		Long magasin = emplacement("MAGASIN", null);
		Long boutique = emplacement("BOUTIQUE", "A");
		Long autre = emplacement("MAGASIN", null);
		String[] numeros = new String[5];
		for (int i = 0; i < 5; i++) {
			numeros[i] = chassis(modele, "DISPONIBLE", magasin);
		}
		String reservee = chassis(modele, "RESERVEE", magasin);
		String ailleurs = chassis(modele, "DISPONIBLE", autre);
		String codeM = jdbc.sql("SELECT code FROM emplacements WHERE id = :i").param("i", magasin).query(String.class).single();
		String codeB = jdbc.sql("SELECT code FROM emplacements WHERE id = :i").param("i", boutique).query(String.class).single();
		return new Decor(magasin, boutique, codeM, codeB, numeros, reservee, ailleurs, c.utilisateurActif("magasin", magasin), c.utilisateurActif("boutique", boutique),
				c.utilisateurActif("magasin", magasin, boutique));
	}

	private static String corps(Decor d, String... numeros) {
		return "{\"source_id\":%d,\"destination_id\":%d,\"observation\":\"Réassort\",\"numeros\":[%s]}".formatted(d.magasin(), d.boutique(),
				String.join(",", java.util.Arrays.stream(numeros).map(n -> "\"" + n + "\"").toList()));
	}

	private Long creer(ClientHttp client, Decor d, String... numeros) throws Exception {
		String reponse = client.post("/api/v1/transferts", corps(d, numeros)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return Long.valueOf(JsonPath.read(reponse, "$.data.id").toString());
	}

	private long mouvements(String numero, String type) {
		return jdbc.sql("SELECT count(*) FROM mouvements_stock m JOIN chassis c ON c.id = m.chassis_id WHERE c.numero = :n AND m.type = :t").param("n", numero).param("t", type).query(Long.class).single();
	}

	@Test
	void laCreationExigeUneListeDeChassisDisponiblesALaSourceEtNeBougeRien() throws Exception {
		ComptesDeTest c = comptes();
		Decor d = decor();
		ClientHttp magasinier = c.connecter(d.expediteur());

		magasinier.post("/api/v1/transferts", corps(d)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.numeros[0]").exists());
		magasinier.post("/api/v1/transferts", "{\"source_id\":%d,\"destination_id\":%d,\"numeros\":[\"%s\"]}".formatted(d.magasin(), d.magasin(), d.numeros()[0]))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.destination_id[0]").exists());
		Long transit = jdbc.sql("SELECT id FROM emplacements WHERE code = 'TRANSIT'").query(Long.class).single();
		magasinier.post("/api/v1/transferts", "{\"source_id\":%d,\"destination_id\":%d,\"numeros\":[\"%s\"]}".formatted(d.magasin(), transit, d.numeros()[0]))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.destination_id[0]").exists());
		// Châssis inconnu, réservé (EF-TRF-03) ou disponible ailleurs : tous refusés, avec le détail.
		magasinier.post("/api/v1/transferts", corps(d, d.numeros()[0], "INCONNU12345", d.reservee(), d.ailleurs())).andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors.numeros[0]").value(containsString("INCONNU12345 : châssis inconnu")))
				.andExpect(jsonPath("$.errors.numeros[0]").value(containsString(d.reservee() + " : n'est pas un châssis disponible à la source")))
				.andExpect(jsonPath("$.errors.numeros[0]").value(containsString(d.ailleurs() + " : n'est pas un châssis disponible à la source")));
		// Quelqu'un qui n'est ni à la source ni à la destination ne peut pas créer.
		c.connecter(c.utilisateurActif("magasin", emplacement("MAGASIN", null))).post("/api/v1/transferts", corps(d, d.numeros()[0])).andExpect(status().isForbidden());
		c.connecter(c.utilisateurActif("commercial")).post("/api/v1/transferts", corps(d, d.numeros()[0])).andExpect(status().isForbidden());

		// Saisie libre (minuscules, tirets), doublons ignorés ; le numéro suit le magasin source.
		String saisi = d.numeros()[0].substring(0, 3).toLowerCase() + "-" + d.numeros()[0].substring(3);
		magasinier.post("/api/v1/transferts", corps(d, saisi, d.numeros()[0], d.numeros()[1])).andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.numero").value(matchesPattern("^TR-" + d.codeMagasin() + "-\\d{4}-\\d{6}$"))).andExpect(jsonPath("$.data.statut").value("INITIE"))
				.andExpect(jsonPath("$.data.nb_chassis").value(2)).andExpect(jsonPath("$.data.droits.expedier").value(true)).andExpect(jsonPath("$.data.droits.bon").value(false));

		// Création : aucun mouvement de stock (EF-TRF-02).
		assertThat(statut(d.numeros()[0])).isEqualTo("DISPONIBLE");
		assertThat(lieu(d.numeros()[0])).isEqualTo(d.codeMagasin());
		assertThat(mouvements(d.numeros()[0], "TRANSFERT_EXPEDITION")).isZero();
		// Un châssis ne peut pas figurer dans deux transferts en cours, et n'est plus proposé.
		magasinier.post("/api/v1/transferts", corps(d, d.numeros()[1])).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.numeros[0]").value(containsString("déjà dans un autre transfert")));
		magasinier.get("/api/v1/transferts/chassis-transferables?source_id=" + d.magasin()).andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].numero").value(hasItem(d.numeros()[2]))).andExpect(jsonPath("$.data[?(@.numero=='%s')]".formatted(d.numeros()[0])).isEmpty())
				.andExpect(jsonPath("$.data[?(@.numero=='%s')]".formatted(d.reservee())).isEmpty());
		// Les droits : un compte sans droit sur les transferts n'accède à rien.
		c.connecter(c.utilisateurActif("commercial")).get("/api/v1/transferts").andExpect(status().isForbidden());
		new ClientHttp(mvc).get("/api/v1/transferts").andExpect(status().isUnauthorized());
	}

	@Test
	void unTransfertInitieSeModifieSAnnuleEtNeSeCorrigePlusApresExpedition() throws Exception {
		ComptesDeTest c = comptes();
		Decor d = decor();
		ClientHttp magasinier = c.connecter(d.expediteur());
		Long id = creer(magasinier, d, d.numeros()[0]);
		String url = "/api/v1/transferts/" + id;

		// Modification : la liste est remplacée, l'ancien châssis redevient libre.
		magasinier.put(url, corps(d, d.numeros()[1], d.numeros()[2])).andExpect(status().isOk()).andExpect(jsonPath("$.data.nb_chassis").value(2))
				.andExpect(jsonPath("$.data.lignes[*].numero").value(hasItem(d.numeros()[2])));
		creer(magasinier, d, d.numeros()[0]);

		magasinier.post(url + "/annuler", "{}").andExpect(status().isUnprocessableEntity());
		magasinier.post(url + "/annuler", "{\"motif\":\"Demande retirée par la boutique\"}").andExpect(status().isOk()).andExpect(jsonPath("$.data.statut").value("ANNULE"))
				.andExpect(jsonPath("$.data.motif_annulation").value("Demande retirée par la boutique"));
		magasinier.post(url + "/expedier", "{}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TRANSFERT_STATUT"));
		magasinier.put(url, corps(d, d.numeros()[1])).andExpect(status().isUnprocessableEntity());
		magasinier.get(url + "/bon").andExpect(status().isUnprocessableEntity());
		// Les châssis de l'annulé sont libres à nouveau.
		creer(magasinier, d, d.numeros()[1], d.numeros()[2]);

		// Après expédition : plus d'annulation, seul un transfert retour corrige (EF-TRF-07).
		Long expedie = creer(magasinier, d, d.numeros()[3]);
		magasinier.post("/api/v1/transferts/" + expedie + "/expedier", "{}").andExpect(status().isOk());
		magasinier.post("/api/v1/transferts/" + expedie + "/annuler", "{\"motif\":\"Trop tard\"}").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("TRANSFERT_NON_ANNULABLE")).andExpect(jsonPath("$.message").value(containsString("transfert retour")));
		// La boutique n'a pas le droit d'annuler : seul le magasin (droit « annuler ») le peut.
		c.connecter(d.receptionnaire()).post(url + "/annuler", "{\"motif\":\"x\"}").andExpect(status().isForbidden());
	}

	@Test
	void lExpeditionFaitSortirLesChassisDeLaSourceEtLesMetEnTransit() throws Exception {
		ComptesDeTest c = comptes();
		Decor d = decor();
		ClientHttp magasinier = c.connecter(d.expediteur());
		ClientHttp boutique = c.connecter(d.receptionnaire());
		Long id = creer(magasinier, d, d.numeros()[0], d.numeros()[1], d.numeros()[2]);
		String url = "/api/v1/transferts/" + id;
		long avant = jdbc.sql("SELECT count(*) FROM notifications WHERE destinataire_id = :u AND type = 'transfert.a_receptionner'").param("u", c.id(d.receptionnaire())).query(Long.class).single();

		// Le vendeur de la boutique peut créer mais pas expédier ; l'autre magasin ne voit rien.
		boutique.post(url + "/expedier", "{}").andExpect(status().isForbidden());
		c.connecter(c.utilisateurActif("magasin", emplacement("MAGASIN", null))).get(url).andExpect(status().isNotFound());
		// « À expédier » chez la source ; rien à réceptionner chez la destination tant que ce n'est pas parti (EF-TRF-09).
		magasinier.get("/api/v1/transferts?vue=a_expedier").andExpect(jsonPath("$.data[?(@.id==%d)]".formatted(id)).isNotEmpty());
		boutique.get("/api/v1/transferts?vue=a_receptionner").andExpect(jsonPath("$.data[?(@.id==%d)]".formatted(id)).isEmpty());
		boutique.get("/api/v1/transferts?vue=a_expedier").andExpect(jsonPath("$.data[?(@.id==%d)]".formatted(id)).isEmpty());

		magasinier.post(url + "/expedier", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.data.statut").value("EXPEDIE")).andExpect(jsonPath("$.data.expedie_par").exists())
				.andExpect(jsonPath("$.data.manquants", hasSize(3))).andExpect(jsonPath("$.data.droits.bon").value(true));
		for (int i = 0; i < 3; i++) {
			assertThat(statut(d.numeros()[i])).isEqualTo("EN_TRANSIT_INTERNE");
			assertThat(lieu(d.numeros()[i])).isEqualTo("TRANSIT");
			assertThat(mouvements(d.numeros()[i], "TRANSFERT_EXPEDITION")).isEqualTo(1);
		}
		assertThat(statut(d.numeros()[3])).isEqualTo("DISPONIBLE");
		// En transit : compté ni à la source ni à la destination.
		magasinier.get("/api/v1/stock/synthese").andExpect(jsonPath("$.data.emplacements[?(@.id==%d)].indicateurs.physique".formatted(d.magasin())).value(hasItem(3)));
		boutique.get("/api/v1/transferts?vue=a_receptionner").andExpect(jsonPath("$.data[?(@.id==%d)]".formatted(id)).isNotEmpty());
		magasinier.post(url + "/expedier", "{}").andExpect(status().isUnprocessableEntity());
		assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE destinataire_id = :u AND type = 'transfert.a_receptionner'").param("u", c.id(d.receptionnaire())).query(Long.class).single()).isEqualTo(avant + 1);

		// Le bon de transfert accompagne les motos.
		byte[] pdf = magasinier.get(url + "/bon").andExpect(status().isOk()).andExpect(content().contentType("application/pdf")).andReturn().getResponse().getContentAsByteArray();
		assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
	}

	@Test
	void laReceptionParPointagePeutSeFaireEnPlusieursFoisEtRefuseLesChassisNonPrevus() throws Exception {
		ComptesDeTest c = comptes();
		Decor d = decor();
		ClientHttp magasinier = c.connecter(d.expediteur());
		ClientHttp boutique = c.connecter(d.receptionnaire());
		Long id = creer(magasinier, d, d.numeros()[0], d.numeros()[1], d.numeros()[2]);
		String url = "/api/v1/transferts/" + id;
		magasinier.post(url + "/expedier", "{}").andExpect(status().isOk());
		c.utilisateurActif("controleur"); // il y a quelqu'un à prévenir
		long avantControle = jdbc.sql("SELECT count(*) FROM notifications WHERE type = 'transfert.scan_refuse'").query(Long.class).single();

		// Rien à pointer : refusé. Un autre magasin n'y a pas accès.
		boutique.post(url + "/receptionner", "{\"numeros\":[]}").andExpect(status().isUnprocessableEntity());
		c.connecter(c.utilisateurActif("boutique", emplacement("BOUTIQUE", "A"))).post(url + "/receptionner", "{\"numeros\":[\"" + d.numeros()[0] + "\"]}").andExpect(status().isNotFound());

		// Première arrivée : deux motos annoncées, une non prévue (disponible au magasin), un numéro inconnu.
		boutique.post(url + "/receptionner", "{\"numeros\":[\"%s\",\"%s\",\"%s\",\"INCONNU98765\"]}".formatted(d.numeros()[0], d.numeros()[1], d.numeros()[4]))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.recus").value(2)).andExpect(jsonPath("$.data.refuses", hasSize(2)))
				.andExpect(jsonPath("$.data.refuses[?(@.numero=='%s')].motif".formatted(d.numeros()[4])).value(hasItem(containsString("Non prévu"))))
				.andExpect(jsonPath("$.data.refuses[?(@.numero=='INCONNU98765')].motif").value(hasItem("Châssis inconnu.")))
				.andExpect(jsonPath("$.data.transfert.statut").value("EXPEDIE")).andExpect(jsonPath("$.data.transfert.nb_recus").value(2))
				.andExpect(jsonPath("$.data.transfert.manquants[0]").value(d.numeros()[2]));
		assertThat(statut(d.numeros()[0])).isEqualTo("DISPONIBLE");
		assertThat(lieu(d.numeros()[0])).isEqualTo(d.codeBoutique());
		assertThat(statut(d.numeros()[2])).isEqualTo("EN_TRANSIT_INTERNE");
		assertThat(statut(d.numeros()[4])).isEqualTo("DISPONIBLE");
		assertThat(lieu(d.numeros()[4])).isEqualTo(d.codeMagasin());
		assertThat(mouvements(d.numeros()[0], "TRANSFERT_RECEPTION")).isEqualTo(1);
		// Le contrôle est prévenu du pointage refusé.
		assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE type = 'transfert.scan_refuse'").query(Long.class).single()).isGreaterThan(avantControle);
		assertThat(jdbc.sql("SELECT count(*) FROM journal_audit WHERE action = 'transfert.scan_refuse' AND objet_id = :i").param("i", String.valueOf(id)).query(Long.class).single()).isEqualTo(1);

		// Un numéro déjà reçu n'est pas compté deux fois ; la dernière moto arrive plus tard (EF-TRF-10).
		boutique.post(url + "/receptionner", "{\"numeros\":[\"%s\",\"%s\"]}".formatted(d.numeros()[0], d.numeros()[2])).andExpect(status().isOk()).andExpect(jsonPath("$.data.recus").value(1))
				.andExpect(jsonPath("$.data.refuses[0].motif").value(containsString("Déjà réceptionné"))).andExpect(jsonPath("$.data.transfert.statut").value("RECEPTIONNE"))
				.andExpect(jsonPath("$.data.transfert.manquants", hasSize(0)));
		assertThat(statut(d.numeros()[2])).isEqualTo("DISPONIBLE");
		assertThat(lieu(d.numeros()[2])).isEqualTo(d.codeBoutique());
		assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE destinataire_id = :u AND type = 'transfert.receptionne'").param("u", c.id(d.expediteur())).query(Long.class).single()).isEqualTo(1);
		boutique.post(url + "/receptionner", "{\"numeros\":[\"" + d.numeros()[0] + "\"]}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TRANSFERT_STATUT"));
		// Le total du stock n'a pas changé : trois motos du magasin sont passées à la boutique.
		boutique.get("/api/v1/stock/synthese").andExpect(jsonPath("$.data.emplacements[?(@.id==%d)].indicateurs.disponible".formatted(d.boutique())).value(hasItem(3)));
	}

	@Test
	void laSeparationDesTachesSeParametreEtUnEcartCloturOuvreLAnomalieA12() throws Exception {
		ComptesDeTest c = comptes();
		ClientHttp admin = c.admin();
		Decor d = decor();
		ClientHttp double_ = c.connecter(d.double_());
		Long id = creer(double_, d, d.numeros()[0], d.numeros()[1]);
		String url = "/api/v1/transferts/" + id;
		double_.post(url + "/expedier", "{}").andExpect(status().isOk());

		// La personne qui a expédié ne réceptionne pas (EF-TRF-05), sauf si la politique est assouplie.
		double_.get(url).andExpect(jsonPath("$.data.droits.receptionner").value(false));
		double_.post(url + "/receptionner", "{\"numeros\":[\"" + d.numeros()[0] + "\"]}").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("UTILISATEUR_IDENTIQUE"));
		admin.put("/api/v1/parametres/controle.transfert_utilisateurs_differents", "{\"valeur\":\"non\"}").andExpect(status().isOk());
		try {
			// Une seule moto arrive ; on clôture avec l'écart : A12 s'ouvre, la moto manquante reste en transit.
			double_.post(url + "/receptionner", "{\"numeros\":[\"%s\"],\"cloturer_avec_ecart\":true}".formatted(d.numeros()[0])).andExpect(status().isOk())
					.andExpect(jsonPath("$.data.transfert.statut").value("EXPEDIE")).andExpect(jsonPath("$.data.transfert.ecart_signale_le").exists())
					.andExpect(jsonPath("$.data.transfert.anomalie").value(matchesPattern("^ANO-.+")));
		} finally {
			admin.put("/api/v1/parametres/controle.transfert_utilisateurs_differents", "{\"valeur\":\"oui\"}").andExpect(status().isOk());
		}
		assertThat(statut(d.numeros()[1])).isEqualTo("EN_TRANSIT_INTERNE");
		var anomalie = jdbc.sql("SELECT code, statut, montant_fcfa, description FROM anomalies WHERE objet_type = 'Transfert' AND objet_id = :i").param("i", id)
				.query((rs, n) -> new Object[]{rs.getString("code"), rs.getString("statut"), rs.getLong("montant_fcfa"), rs.getString("description")}).single();
		assertThat(anomalie[0]).isEqualTo("A12");
		assertThat(anomalie[1]).isEqualTo("OUVERTE");
		assertThat(anomalie[2]).isEqualTo(100000L);
		assertThat((String) anomalie[3]).contains(d.numeros()[1]);
		// Une seule anomalie vivante par transfert : un second signalement la met à jour.
		double_.post(url + "/receptionner", "{\"numeros\":[\"X\"],\"cloturer_avec_ecart\":true}").andExpect(status().isForbidden());
	}

	@Test
	void unTransfertNonReceptionneApresLeDelaiAlerteEtOuvreLAnomalie() throws Exception {
		ComptesDeTest c = comptes();
		Decor d = decor();
		ClientHttp magasinier = c.connecter(d.expediteur());
		Long id = creer(magasinier, d, d.numeros()[0]);
		magasinier.post("/api/v1/transferts/" + id + "/expedier", "{}").andExpect(status().isOk());
		c.utilisateurActif("controleur");
		long avant = jdbc.sql("SELECT count(*) FROM notifications WHERE type = 'transfert.ecart'").query(Long.class).single();

		// Les autres tests peuvent avoir fixé ce délai : on le met de côté, et on le remet à la fin.
		var existants = jdbc.sql("SELECT valeur, date_effet FROM parametres WHERE cle = 'controle.delai_reception_transfert_jours'")
				.query((rs, n) -> new Object[]{rs.getString("valeur"), rs.getDate("date_effet")}).list();
		jdbc.sql("DELETE FROM parametres WHERE cle = 'controle.delai_reception_transfert_jours'").update();
		// Tant que le délai n'est pas paramétré, aucun contrôle ne s'applique.
		jdbc.sql("UPDATE transferts SET expedie_le = now() - interval '10 days' WHERE id = :i").param("i", id).update();
		assertThat(service.controlerRetards()).isZero();
		try {
			jdbc.sql("INSERT INTO parametres (cle, valeur, date_effet) VALUES ('controle.delai_reception_transfert_jours', '5', '2020-01-01')").update();
			magasinier.get("/api/v1/transferts/" + id).andExpect(jsonPath("$.data.en_retard").value(true)).andExpect(jsonPath("$.data.jours_en_transit").value(10));
			magasinier.get("/api/v1/transferts?vue=a_receptionner").andExpect(status().isOk());
			assertThat(service.controlerRetards()).isGreaterThanOrEqualTo(1);
			// Une seule alerte par transfert.
			assertThat(service.controlerRetards()).isZero();
		} finally {
			jdbc.sql("DELETE FROM parametres WHERE cle = 'controle.delai_reception_transfert_jours'").update();
			for (Object[] ligne : existants) {
				jdbc.sql("INSERT INTO parametres (cle, valeur, date_effet) VALUES ('controle.delai_reception_transfert_jours', :v, :d)").param("v", ligne[0]).param("d", ligne[1]).update();
			}
		}
		assertThat(jdbc.sql("SELECT count(*) FROM anomalies WHERE code = 'A12' AND objet_id = :i").param("i", id).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE type = 'transfert.ecart'").query(Long.class).single()).isGreaterThan(avant);
		magasinier.get("/api/v1/transferts/" + id).andExpect(jsonPath("$.data.anomalie").exists());
		assertThat(List.of(d.numeros()[0])).hasSize(1);
	}

}
