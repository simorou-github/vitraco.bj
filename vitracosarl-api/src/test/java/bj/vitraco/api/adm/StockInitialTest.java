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

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Reprise du stock (US-006, SRS 14.2) : import initial par châssis, puis rapprochement par pointage sur site. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class StockInitialTest {

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
		return "SI" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
	}

	private Long emplacement(String type, String modeBoutique, String code) {
		return jdbc.sql("INSERT INTO emplacements (code, nom, type, mode_boutique, actif) VALUES (:c, :n, :t, :m, true) RETURNING id")
				.param("c", code).param("n", "Lieu " + code).param("t", type).param("m", modeBoutique).query(Long.class).single();
	}

	private String modele() throws Exception {
		String code = unique("SI");
		comptes().admin().post("/api/v1/modeles", "{\"marque\":\"Honda\",\"modele\":\"Initial\",\"code_article\":\"%s\",\"format_chassis\":\"^SI[A-Z0-9]{12}$\",\"prix_vente\":1000000,\"prix_minimum\":900000}".formatted(code))
				.andExpect(status().isCreated());
		return code;
	}

	private byte[] csv(String... lignes) {
		return ("numero_chassis;code_article;emplacement;statut;date_entree;cout_revient;numero_moteur;couleur;motif_blocage\n" + String.join("\n", lignes) + "\n").getBytes(StandardCharsets.UTF_8);
	}

	@Test
	void leStockInitialSImporteParChassisAvecRapportLignesRejeteesEtSansDoublon() throws Exception {
		ComptesDeTest c = comptes();
		ClientHttp admin = c.admin();
		String article = modele();
		String codeM = unique("IM");
		String codeB = unique("IB");
		emplacement("MAGASIN", null, codeM);
		emplacement("BOUTIQUE", "A", codeB);
		String a = numero(), b = numero(), d = numero(), e = numero(), f = numero(), existant = numero();
		String hier = LocalDate.now().minusDays(40).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
		String demain = LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
		// Un châssis déjà connu du système.
		jdbc.sql("INSERT INTO chassis (numero, modele_id, statut, emplacement_id, date_entree) VALUES (:n, (SELECT id FROM modeles WHERE code_article = :c), 'DISPONIBLE', (SELECT id FROM emplacements WHERE code = :e), current_date)")
				.param("n", existant).param("c", article).param("e", codeM).update();

		byte[] fichier = csv(
				a + ";" + article + ";" + codeM + ";disponible;" + hier + ";1 250 000;MOT1;Rouge;",           // 2  bon, avec date, coût et couleur
				b + ";" + article + ";" + codeB + ";;;;;;",                                                  // 3  bon, valeurs par défaut
				d + ";" + article + ";" + codeM + ";bloqué;;980000;;;Cadre fissuré",                          // 4  bon, bloqué avec motif
				a + ";" + article + ";" + codeM + ";;;;;;",                                                  // 5  doublon dans le fichier
				existant + ";" + article + ";" + codeM + ";;;;;;",                                           // 6  déjà au stock
				e + ";INCONNU-XYZ;" + codeM + ";;;;;;",                                                     // 7  article inconnu
				e + ";" + article + ";LIEU-FANTOME;;;;;;",                                                  // 8  emplacement inconnu
				e + ";" + article + ";TRANSIT;;;;;;",                                                       // 9  transit : ni magasin ni boutique
				e + ";" + article + ";" + codeM + ";bloqué;;;;;",                                           // 10 bloqué sans motif
				e + ";" + article + ";" + codeM + ";réservée;;;;;",                                         // 11 statut non repris
				e + ";" + article + ";" + codeM + ";;31/02/2026;;;;",                                       // 12 date illisible
				e + ";" + article + ";" + codeM + ";;" + demain + ";;;;",                                    // 13 date future
				e + ";" + article + ";" + codeM + ";;;-5;;;",                                               // 14 coût négatif
				"XX1;" + article + ";" + codeM + ";;;;;;",                                                  // 15 format du modèle
				";" + article + ";" + codeM + ";;;;;;",                                                     // 16 numéro manquant
				";;;;;;;;",                                                                                   // 17 ligne vide : ignorée
				f + ";" + article + ";" + codeM + ";;;;;;");                                                 // 18 bon

		// Les droits : le magasin n'importe pas.
		c.connecter(c.utilisateurActif("magasin")).postFichier("/api/v1/stock/imports", "stock.csv", fichier).andExpect(status().isForbidden());
		admin.postFichier("/api/v1/stock/imports", "stock.pdf", fichier).andExpect(status().isUnprocessableEntity());
		admin.postFichier("/api/v1/stock/imports", "stock.csv", "a;b\n1;2\n".getBytes()).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.fichier[0]").value(containsString("Colonnes attendues")));

		// Simulation : le rapport complet, mais rien n'est créé.
		long avant = jdbc.sql("SELECT count(*) FROM chassis").query(Long.class).single();
		admin.postFichier("/api/v1/stock/imports", "stock.csv", fichier, "simulation", "true").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.simulation").value(true)).andExpect(jsonPath("$.data.nb_lignes").value(16)).andExpect(jsonPath("$.data.nb_importees").value(4))
				.andExpect(jsonPath("$.data.nb_rejetees").value(12)).andExpect(jsonPath("$.data.id").doesNotExist());
		assertThat(jdbc.sql("SELECT count(*) FROM chassis").query(Long.class).single()).isEqualTo(avant);

		// Import réel : les bonnes lignes passent, les mauvaises sont rejetées avec leur numéro de ligne et leur motif.
		String reponse = admin.postFichier("/api/v1/stock/imports", "stock.csv", fichier).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.nb_importees").value(4)).andExpect(jsonPath("$.data.nb_rejetees").value(12))
				.andExpect(jsonPath("$.data.rejets[?(@.ligne==5)].motif").value(hasItem("doublon dans le fichier")))
				.andExpect(jsonPath("$.data.rejets[?(@.ligne==6)].motif").value(hasItem(containsString("déjà enregistré"))))
				.andExpect(jsonPath("$.data.rejets[?(@.ligne==7)].motif").value(hasItem("code article inconnu : INCONNU-XYZ")))
				.andExpect(jsonPath("$.data.rejets[?(@.ligne==8)].motif").value(hasItem(containsString("emplacement inconnu"))))
				.andExpect(jsonPath("$.data.rejets[?(@.ligne==9)].motif").value(hasItem(containsString("ni un magasin ni une boutique"))))
				.andExpect(jsonPath("$.data.rejets[?(@.ligne==10)].motif").value(hasItem("un châssis bloqué exige un motif")))
				.andExpect(jsonPath("$.data.rejets[?(@.ligne==11)].motif").value(hasItem(containsString("statut non repris"))))
				.andExpect(jsonPath("$.data.rejets[?(@.ligne==12)].motif").value(hasItem(containsString("date d'entrée illisible"))))
				.andExpect(jsonPath("$.data.rejets[?(@.ligne==13)].motif").value(hasItem(containsString("dans le futur"))))
				.andExpect(jsonPath("$.data.rejets[?(@.ligne==14)].motif").value(hasItem("coût de revient négatif")))
				.andExpect(jsonPath("$.data.rejets[?(@.ligne==15)].motif").value(hasItem(containsString("format"))))
				.andExpect(jsonPath("$.data.rejets[?(@.ligne==16)].motif").value(hasItem("numéro de châssis manquant")))
				.andExpect(jsonPath("$.data.repartition", hasSize(2))).andExpect(jsonPath("$.data.valeur_cout_fcfa").value(1250000 + 980000))
				.andReturn().getResponse().getContentAsString();
		Long importId = Long.valueOf(JsonPath.read(reponse, "$.data.id").toString());

		// Chaque moto est au bon endroit, avec le bon statut, la bonne date, son coût, et un mouvement qui renvoie à l'import.
		assertThat(jdbc.sql("SELECT statut FROM chassis WHERE numero = :n").param("n", a).query(String.class).single()).isEqualTo("DISPONIBLE");
		assertThat(jdbc.sql("SELECT statut FROM chassis WHERE numero = :n").param("n", d).query(String.class).single()).isEqualTo("BLOQUEE");
		assertThat(jdbc.sql("SELECT e.code FROM chassis c JOIN emplacements e ON e.id = c.emplacement_id WHERE c.numero = :n").param("n", b).query(String.class).single()).isEqualTo(codeB);
		assertThat(jdbc.sql("SELECT cout_revient FROM chassis WHERE numero = :n").param("n", a).query(Long.class).single()).isEqualTo(1_250_000L);
		assertThat(jdbc.sql("SELECT couleur FROM chassis WHERE numero = :n").param("n", a).query(String.class).single()).isEqualTo("Rouge");
		assertThat(jdbc.sql("SELECT current_date - date_entree FROM chassis WHERE numero = :n").param("n", a).query(Long.class).single()).isEqualTo(40L);
		assertThat(jdbc.sql("SELECT count(*) FROM mouvements_stock m JOIN chassis c ON c.id = m.chassis_id WHERE c.numero = :n AND m.type = 'CREATION' AND m.document_type = 'IMPORT_STOCK' AND m.document_id = :i")
				.param("n", a).param("i", importId).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT motif FROM mouvements_stock m JOIN chassis c ON c.id = m.chassis_id WHERE c.numero = :n").param("n", d).query(String.class).single()).isEqualTo("Cadre fissuré");
		assertThat(jdbc.sql("SELECT count(*) FROM journal_audit WHERE action = 'stock.import_initial' AND objet_id = :i").param("i", String.valueOf(importId)).query(Long.class).single()).isEqualTo(1);

		// Aucun châssis en double : rejouer le même fichier n'importe plus rien.
		admin.postFichier("/api/v1/stock/imports", "stock.csv", fichier).andExpect(status().isOk()).andExpect(jsonPath("$.data.nb_importees").value(0))
				.andExpect(jsonPath("$.data.rejets[?(@.ligne==2)].motif").value(hasItem(containsString("déjà enregistré"))));
		// Le rapport reste consultable, avec l'historique des imports.
		admin.get("/api/v1/stock/imports/" + importId).andExpect(status().isOk()).andExpect(jsonPath("$.data.rejets", hasSize(12))).andExpect(jsonPath("$.data.nb_importees").value(4));
		admin.get("/api/v1/stock/imports").andExpect(status().isOk()).andExpect(jsonPath("$.data[?(@.id==%d)].nb_rejetees".formatted(importId)).value(hasItem(12)));
		admin.get("/api/v1/stock/imports/999999").andExpect(status().isNotFound());
	}

	@Test
	void leModeleExcelSeTelechargeEtSeRelitDirectement() throws Exception {
		ClientHttp admin = comptes().admin();
		byte[] xlsx = admin.get("/api/v1/stock/imports/modele").andExpect(status().isOk())
				.andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).andReturn().getResponse().getContentAsByteArray();
		assertThat(xlsx.length).isGreaterThan(1000);
		// Le modèle téléchargé est un vrai .xlsx : son exemple est lu, puis rejeté parce que son article n'existe pas au catalogue.
		admin.postFichier("/api/v1/stock/imports", "modele.xlsx", xlsx, "simulation", "true").andExpect(status().isOk()).andExpect(jsonPath("$.data.nb_lignes").value(1))
				.andExpect(jsonPath("$.data.rejets[0].motif").value(containsString("code article inconnu")));
	}

	@Test
	void lePointageSurSiteComparesStockAttenduEtStockReelEtConserveLeRapport() throws Exception {
		ComptesDeTest c = comptes();
		String article = modele();
		Long magasin = emplacement("MAGASIN", null, unique("PM"));
		Long autre = emplacement("MAGASIN", null, unique("PA"));
		Long modeleId = jdbc.sql("SELECT id FROM modeles WHERE code_article = :c").param("c", article).query(Long.class).single();
		String[] presents = {numero(), numero(), numero(), numero()};
		for (int i = 0; i < presents.length; i++) {
			jdbc.sql("INSERT INTO chassis (numero, modele_id, statut, emplacement_id, date_entree) VALUES (:n, :m, :s, :e, current_date)")
					.param("n", presents[i]).param("m", modeleId).param("s", i == 3 ? "BLOQUEE" : "DISPONIBLE").param("e", magasin).update();
		}
		String ailleurs = numero();
		jdbc.sql("INSERT INTO chassis (numero, modele_id, statut, emplacement_id, date_entree) VALUES (:n, :m, 'DISPONIBLE', :e, current_date)").param("n", ailleurs).param("m", modeleId).param("e", autre).update();
		String livree = numero();
		jdbc.sql("INSERT INTO chassis (numero, modele_id, statut, emplacement_id, date_entree) VALUES (:n, :m, 'LIVREE', :e, current_date)").param("n", livree).param("m", modeleId).param("e", magasin).update();

		ClientHttp magasinier = c.connecter(c.utilisateurActif("magasin", magasin));
		// Trois motos conformes, une moto d'un autre site, une inconnue : il manque la quatrième (bloquée, elle est au stock physique).
		String corps = "{\"emplacement_id\":%d,\"numeros\":[\"%s\",\"%s\",\"%s\",\"%s\",\"INCONNU-99\",\"%s\"]}".formatted(magasin, presents[0], presents[1].toLowerCase(), presents[2], ailleurs, presents[0]);
		String reponse = magasinier.post("/api/v1/stock/rapprochements", corps).andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.nb_attendus").value(4)).andExpect(jsonPath("$.data.nb_pointes").value(5)).andExpect(jsonPath("$.data.nb_conformes").value(3))
				.andExpect(jsonPath("$.data.ecart_nul").value(false)).andExpect(jsonPath("$.data.manquants", hasSize(1))).andExpect(jsonPath("$.data.manquants[0].numero").value(presents[3]))
				.andExpect(jsonPath("$.data.manquants[0].detail").value(containsString("Bloquée")))
				.andExpect(jsonPath("$.data.inattendus", hasSize(2)))
				.andExpect(jsonPath("$.data.inattendus[?(@.numero=='%s')].detail".formatted(ailleurs)).value(hasItem(containsString("enregistré : Disponible à"))))
				.andExpect(jsonPath("$.data.inattendus[?(@.numero=='INCONNU99')].detail").value(hasItem(containsString("inconnu du système")))).andReturn().getResponse().getContentAsString();
		Long id = Long.valueOf(JsonPath.read(reponse, "$.data.id").toString());
		// Une moto livrée n'est plus au stock : elle n'est pas attendue, elle n'est donc pas « manquante ».
		assertThat(reponse).doesNotContain(livree);
		// Le pointage ne corrige rien.
		assertThat(jdbc.sql("SELECT count(*) FROM chassis WHERE emplacement_id = :e").param("e", magasin).query(Long.class).single()).isEqualTo(5L);

		// Le rapport est conservé ; un second pointage complet donne un écart nul : le stock d'ouverture est validé.
		magasinier.get("/api/v1/stock/rapprochements/" + id).andExpect(status().isOk()).andExpect(jsonPath("$.data.emplacement").exists());
		magasinier.post("/api/v1/stock/rapprochements", "{\"emplacement_id\":%d,\"numeros\":[\"%s\",\"%s\",\"%s\",\"%s\"]}".formatted(magasin, presents[0], presents[1], presents[2], presents[3]))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.data.ecart_nul").value(true)).andExpect(jsonPath("$.data.nb_conformes").value(4));
		magasinier.get("/api/v1/stock/rapprochements?emplacement_id=" + magasin).andExpect(jsonPath("$.data", hasSize(2))).andExpect(jsonPath("$.data[0].ecart_nul").value(true))
				.andExpect(jsonPath("$.data[1].nb_manquants").value(1));
		// Validations, périmètre et droits.
		magasinier.post("/api/v1/stock/rapprochements", "{\"emplacement_id\":%d,\"numeros\":[]}".formatted(magasin)).andExpect(status().isUnprocessableEntity());
		magasinier.post("/api/v1/stock/rapprochements", "{\"emplacement_id\":%d,\"numeros\":[\"%s\"]}".formatted(autre, ailleurs)).andExpect(status().isNotFound());
		ClientHttp ailleursClient = c.connecter(c.utilisateurActif("magasin", autre));
		ailleursClient.get("/api/v1/stock/rapprochements/" + id).andExpect(status().isNotFound());
		ailleursClient.get("/api/v1/stock/rapprochements").andExpect(jsonPath("$.data", hasSize(0)));
		c.connecter(c.utilisateurActif(c.role(false, "stock.voir"))).post("/api/v1/stock/rapprochements", corps).andExpect(status().isForbidden());
		new ClientHttp(mvc).get("/api/v1/stock/rapprochements").andExpect(status().isUnauthorized());
	}

}
