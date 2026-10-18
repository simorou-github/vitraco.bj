package bj.vitraco.api.adm;

import bj.vitraco.api.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ArrivagesTest {

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

	/** Fournisseur, deux modèles (A : 3 commandés avec un format de châssis, B : 2 commandés) et une commande confirmée. */
	private record Dossier(Long commande, Long fournisseur, Long modeleA, Long modeleB, String codeA, String codeB, String proforma) {
	}

	private Dossier dossier(boolean confirmer) throws Exception {
		ClientHttp admin = comptes().admin();
		String nom = "Fournisseur " + unique("");
		admin.post("/api/v1/fournisseurs", "{\"raison_sociale\":\"%s\",\"pays\":\"Chine\",\"devise\":\"USD\",\"contacts\":[]}".formatted(nom)).andExpect(status().isCreated());
		Long fournisseur = jdbc.sql("SELECT id FROM fournisseurs WHERE raison_sociale = :n").param("n", nom).query(Long.class).single();
		String a = unique("MA");
		String b = unique("MB");
		admin.post("/api/v1/modeles", "{\"marque\":\"Honda\",\"modele\":\"A\",\"code_article\":\"%s\",\"format_chassis\":\"^LWB[A-Z0-9]{9}$\",\"prix_vente\":1000000,\"prix_minimum\":900000}".formatted(a))
				.andExpect(status().isCreated());
		admin.post("/api/v1/modeles", "{\"marque\":\"Honda\",\"modele\":\"B\",\"code_article\":\"%s\",\"prix_vente\":1000000,\"prix_minimum\":900000}".formatted(b))
				.andExpect(status().isCreated());
		Long ma = jdbc.sql("SELECT id FROM modeles WHERE code_article = :c").param("c", a).query(Long.class).single();
		Long mb = jdbc.sql("SELECT id FROM modeles WHERE code_article = :c").param("c", b).query(Long.class).single();
		String reponse = admin.post("/api/v1/commandes-fournisseur", """
				{"fournisseur_id":%d,"devise":"USD","taux_change":600,"incoterm":"FOB","base_prix":"FOB",
				 "lignes":[{"modele_id":%d,"quantite":3,"prix_unitaire":1000},{"modele_id":%d,"quantite":2,"prix_unitaire":800}]}""".formatted(fournisseur, ma, mb))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		Long commande = Long.valueOf(JsonPath.read(reponse, "$.data.id").toString());
		String proforma = "PI-" + unique("");
		if (confirmer) {
			admin.post("/api/v1/commandes-fournisseur/" + commande + "/avancer", "{\"reference_proforma\":\"%s\"}".formatted(proforma)).andExpect(status().isOk());
		}
		return new Dossier(commande, fournisseur, ma, mb, a, b, proforma);
	}

	private Long creerArrivage(ClientHttp admin, Dossier d, String corps) throws Exception {
		String reponse = admin.post("/api/v1/commandes-fournisseur/" + d.commande() + "/arrivages", corps).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return Long.valueOf(JsonPath.read(reponse, "$.data.id").toString());
	}

	private static String bl() {
		return "{\"numero_bl\":\"" + unique("BL") + "\"}";
	}

	private static String numero() {
		return "LWB" + UUID.randomUUID().toString().replace("-", "").substring(0, 9).toUpperCase();
	}

	@Test
	void unArrivageSeCreeApresLaConfirmationAvecSesDatesEtSonRetardSeMesure() throws Exception {
		ClientHttp admin = comptes().admin();
		Dossier avant = dossier(false);
		admin.post("/api/v1/commandes-fournisseur/" + avant.commande() + "/arrivages", bl()).andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("COMMANDE_NON_OUVERTE_AUX_ARRIVAGES"));

		Dossier d = dossier(true);
		LocalDate aujourdhui = LocalDate.now();
		String corps = """
				{"numero_bl":"%s","conteneur":"MSKU1234567","port":"Cotonou","date_depart_prevue":"%s","date_arrivee_prevue":"%s","observation":"Premier lot"}"""
				.formatted("bl-" + unique(""), aujourdhui.minusDays(30), aujourdhui.minusDays(5));
		String reponse = admin.post("/api/v1/commandes-fournisseur/" + d.commande() + "/arrivages", corps).andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.etat").value("EN_TRANSIT")).andExpect(jsonPath("$.data.retard_jours").value(5))
				.andExpect(jsonPath("$.data.statut_douane").value("NON_DEMARREE")).andExpect(jsonPath("$.data.conteneur").value("MSKU1234567"))
				.andExpect(jsonPath("$.data.commande_numero").exists()).andReturn().getResponse().getContentAsString();
		Long id = Long.valueOf(JsonPath.read(reponse, "$.data.id").toString());
		String numeroBl = JsonPath.read(reponse, "$.data.numero_bl");

		// Un B/L ne se saisit qu'une fois par commande, quelle que soit la casse ; les dates doivent se suivre.
		admin.post("/api/v1/commandes-fournisseur/" + d.commande() + "/arrivages", "{\"numero_bl\":\"" + numeroBl.toUpperCase() + "\"}").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors.numero_bl[0]").exists());
		admin.post("/api/v1/commandes-fournisseur/" + d.commande() + "/arrivages",
				"{\"numero_bl\":\"X\",\"date_depart_prevue\":\"2026-05-10\",\"date_arrivee_prevue\":\"2026-05-01\"}").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors.date_arrivee_prevue[0]").exists());
		admin.post("/api/v1/commandes-fournisseur/" + d.commande() + "/arrivages", "{}").andExpect(status().isUnprocessableEntity());

		// Plusieurs arrivages par commande ; la liste « en cours » montre le retard.
		creerArrivage(admin, d, bl());
		admin.get("/api/v1/arrivages?commande_id=" + d.commande()).andExpect(jsonPath("$.data", hasSize(2)));
		admin.get("/api/v1/arrivages?en_cours=true&search=MSKU1234567").andExpect(jsonPath("$.data[0].id").value(id.intValue())).andExpect(jsonPath("$.data[0].retard_jours").value(5));
		admin.put("/api/v1/arrivages/" + id, "{\"numero_bl\":\"%s\",\"date_arrivee_reelle\":\"%s\"}".formatted(numeroBl, aujourdhui)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.etat").value("AU_PORT")).andExpect(jsonPath("$.data.retard_jours").value(0));
		assertThat(jdbc.sql("SELECT count(*) FROM journal_audit WHERE action = 'arrivage.cree' AND objet_id = :i").param("i", String.valueOf(id)).query(Long.class).single()).isEqualTo(1);
		new ClientHttp(mvc).get("/api/v1/arrivages").andExpect(status().isUnauthorized());
	}

	@Test
	void lesArrivagesCommandentLesEtapesDeLaCommande() throws Exception {
		ClientHttp admin = comptes().admin();
		Dossier d = dossier(true);
		String url = "/api/v1/commandes-fournisseur/" + d.commande();

		admin.post(url + "/avancer", "{}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ETAPE_REFUSEE"));
		String bl = unique("BL");
		Long arrivage = creerArrivage(admin, d, "{\"numero_bl\":\"" + bl + "\"}");
		admin.post(url + "/avancer", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.data.statut").value("EN_TRANSIT"));
		admin.post(url + "/avancer", "{}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.message").value(containsString("date d'arrivée réelle")));
		admin.put("/api/v1/arrivages/" + arrivage, "{\"numero_bl\":\"%s\",\"date_arrivee_reelle\":\"%s\"}".formatted(bl, LocalDate.now())).andExpect(status().isOk());
		admin.post(url + "/avancer", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.data.statut").value("AU_PORT"));
		admin.post(url + "/avancer", "{}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.message").value(containsString("dédouanement")));
		admin.post("/api/v1/arrivages/" + arrivage + "/douane", "{}").andExpect(status().isOk());
		admin.post(url + "/avancer", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.data.statut").value("DEDOUANEMENT"));
		admin.post(url + "/avancer", "{}").andExpect(status().isUnprocessableEntity());
		admin.post("/api/v1/arrivages/" + arrivage + "/douane", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.data.etat").value("A_RECEPTIONNER"));
		admin.post(url + "/avancer", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.data.statut").value("A_RECEPTIONNER"));
		admin.get(url).andExpect(jsonPath("$.data.nb_arrivages").value(1));
	}

	@Test
	void leDedouanementAvanceSansSauterEtSansArriveeNiRetraitDeLaDate() throws Exception {
		ClientHttp admin = comptes().admin();
		Dossier d = dossier(true);
		String bl = unique("BL");
		Long id = creerArrivage(admin, d, "{\"numero_bl\":\"" + bl + "\"}");

		admin.post("/api/v1/arrivages/" + id + "/douane", "{}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ARRIVEE_NON_SAISIE"));
		admin.put("/api/v1/arrivages/" + id, "{\"numero_bl\":\"%s\",\"date_arrivee_reelle\":\"%s\"}".formatted(bl, LocalDate.now())).andExpect(status().isOk());
		admin.post("/api/v1/arrivages/" + id + "/douane", "{\"statut\":\"TERMINEE\"}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ETAPE_SAUTEE"));
		admin.post("/api/v1/arrivages/" + id + "/douane", "{\"statut\":\"EN_COURS\"}").andExpect(status().isOk()).andExpect(jsonPath("$.data.etat").value("DEDOUANEMENT"));
		// Dédouanement engagé : la date d'arrivée réelle ne se retire plus.
		admin.put("/api/v1/arrivages/" + id, "{\"numero_bl\":\"%s\"}".formatted(bl)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.date_arrivee_reelle[0]").exists());
		admin.post("/api/v1/arrivages/" + id + "/douane", "{}").andExpect(status().isOk());
		admin.post("/api/v1/arrivages/" + id + "/douane", "{}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DOUANE_TERMINEE"));
		assertThat(jdbc.sql("SELECT count(*) FROM journal_audit WHERE action = 'arrivage.douane_avancee' AND objet_id = :i").param("i", String.valueOf(id)).query(Long.class).single()).isEqualTo(2);
	}

	@Test
	void lesFraisPortentDeviseTauxEtPieceEtAlimententLeCoutDeRevientProvisoire() throws Exception {
		ComptesDeTest c = comptes();
		ClientHttp admin = c.admin();
		Dossier d = dossier(true);
		Long id = creerArrivage(admin, d, bl());
		String url = "/api/v1/arrivages/" + id + "/frais";

		// 3 × 1 000 USD + 2 × 800 USD = 4 600 USD = 2 760 000 FCFA de marchandise.
		admin.post(url, "{\"type\":\"FRET\",\"montant\":1200,\"devise\":\"usd\",\"taux_change\":600,\"reference_piece\":\"FAC-FRET-1\"}").andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.frais", hasSize(1))).andExpect(jsonPath("$.data.frais[0].montant_fcfa").value(720_000))
				.andExpect(jsonPath("$.data.frais[0].libelle").value("Fret")).andExpect(jsonPath("$.data.frais[0].sans_piece").value(false))
				.andExpect(jsonPath("$.data.total_frais_fcfa").value(720_000)).andExpect(jsonPath("$.data.frais_sans_piece").value(0));
		admin.post(url, "{\"type\":\"DROITS_TAXES\",\"libelle\":\"Droits de douane\",\"montant\":350000,\"devise\":\"XOF\"}").andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.total_frais_fcfa").value(1_070_000)).andExpect(jsonPath("$.data.frais_sans_piece").value(1));
		// Un frais sans pièce est signalé, et le coût provisoire se recalcule.
		admin.get("/api/v1/commandes-fournisseur/" + d.commande()).andExpect(jsonPath("$.data.total_fcfa").value(2_760_000)).andExpect(jsonPath("$.data.frais_fcfa").value(1_070_000))
				.andExpect(jsonPath("$.data.cout_revient_provisoire_fcfa").value(3_830_000)).andExpect(jsonPath("$.data.cout_moyen_moto_fcfa").value(766_000))
				.andExpect(jsonPath("$.data.frais_sans_piece").value(1));

		admin.post(url, "{\"type\":\"TRANSIT\",\"montant\":100,\"devise\":\"EUR\"}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.taux_change[0]").exists());
		admin.post(url, "{\"type\":\"PORT\",\"montant\":100,\"devise\":\"XOF\",\"taux_change\":2}").andExpect(status().isUnprocessableEntity());
		admin.post(url, "{\"type\":\"PORT\",\"montant\":-5,\"devise\":\"XOF\"}").andExpect(status().isUnprocessableEntity());
		admin.post(url, "{\"type\":\"CARBURANT\",\"montant\":5,\"devise\":\"XOF\"}").andExpect(status().isBadRequest());
		admin.post(url, "{\"montant\":5,\"devise\":\"XOF\"}").andExpect(status().isUnprocessableEntity());

		// La pièce arrive après coup ; le frais est corrigé et tracé, jamais supprimé.
		Long fraisSansPiece = jdbc.sql("SELECT id FROM frais_arrivage WHERE arrivage_id = :a AND reference_piece IS NULL").param("a", id).query(Long.class).single();
		admin.put(url + "/" + fraisSansPiece, "{\"type\":\"DROITS_TAXES\",\"montant\":360000,\"devise\":\"XOF\",\"reference_piece\":\"QUITT-77\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.frais_sans_piece").value(0)).andExpect(jsonPath("$.data.total_frais_fcfa").value(1_080_000));
		admin.post(url + "/" + fraisSansPiece + "/annuler", "{}").andExpect(status().isUnprocessableEntity());
		admin.post(url + "/" + fraisSansPiece + "/annuler", "{\"motif\":\"Doublon de la quittance 77\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.total_frais_fcfa").value(720_000)).andExpect(jsonPath("$.data.frais[?(@.id==%d)].annule".formatted(fraisSansPiece)).value(hasItem(true)));
		admin.put(url + "/" + fraisSansPiece, "{\"type\":\"PORT\",\"montant\":1,\"devise\":\"XOF\"}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("FRAIS_ANNULE"));
		admin.get("/api/v1/commandes-fournisseur/" + d.commande()).andExpect(jsonPath("$.data.cout_revient_provisoire_fcfa").value(3_480_000));
		assertThat(jdbc.sql("SELECT count(*) FROM journal_audit WHERE action LIKE 'frais_arrivage.%' AND objet_id = :i").param("i", String.valueOf(id)).query(Long.class).single()).isEqualTo(4);
	}

	@Test
	void lesFraisSontDesElementsDuCoutDeRevientDoncMasquesAuMagasin() throws Exception {
		ComptesDeTest c = comptes();
		ClientHttp admin = c.admin();
		Dossier d = dossier(true);
		Long id = creerArrivage(admin, d, bl());
		admin.post("/api/v1/arrivages/" + id + "/frais", "{\"type\":\"FRET\",\"montant\":1200,\"devise\":\"USD\",\"taux_change\":600}").andExpect(status().isCreated());

		ClientHttp magasin = c.connecter(c.utilisateurActif("magasin"));
		magasin.get("/api/v1/arrivages/" + id).andExpect(status().isOk()).andExpect(jsonPath("$.data.frais_visibles").value(false)).andExpect(jsonPath("$.data.frais", hasSize(0)))
				.andExpect(jsonPath("$.data.total_frais_fcfa").doesNotExist()).andExpect(jsonPath("$.data.numero_bl").exists());
		magasin.get("/api/v1/commandes-fournisseur/" + d.commande()).andExpect(jsonPath("$.data.frais_fcfa").doesNotExist()).andExpect(jsonPath("$.data.cout_revient_provisoire_fcfa").doesNotExist());
		magasin.post("/api/v1/arrivages/" + id + "/frais", "{\"type\":\"FRET\",\"montant\":1,\"devise\":\"XOF\"}").andExpect(status().isForbidden());
		magasin.post("/api/v1/arrivages/" + id + "/douane", "{}").andExpect(status().isForbidden());
		magasin.post("/api/v1/commandes-fournisseur/" + d.commande() + "/arrivages", bl()).andExpect(status().isForbidden());
	}

	private static String csv(String... lignes) {
		return String.join("\n", lignes) + "\n";
	}

	@Test
	void leColisageCsvCreeLesChassisAnnoncesEtRejetteLesLignesFautivesAvecLeurMotif() throws Exception {
		ClientHttp admin = comptes().admin();
		Dossier d = dossier(true);
		Long id = creerArrivage(admin, d, bl());
		String n1 = numero();
		String n2 = numero();
		String n3 = numero();
		String nb = numero();
		String fichier = csv("numero_chassis;code_article;numero_moteur;couleur",
				n1 + ";" + d.codeA() + ";MOT-1;Rouge",
				n2.substring(0, 3) + "-" + n2.substring(3) + ";" + d.codeA().toLowerCase() + ";;Noir",
				n1 + ";" + d.codeA() + ";;",                      // ligne 4 : doublon dans le fichier
				"XYZ123456789;" + d.codeA() + ";;",                // ligne 5 : format du modèle
				n3 + ";CODE-INCONNU;;",                            // ligne 6 : code article inconnu
				";" + d.codeA() + ";;",                            // ligne 7 : numéro manquant
				nb + ";" + d.codeB() + ";;Bleu",
				";;;",                                              // ligne vide : ignorée
				numero() + ";" + d.codeA() + ";;",                 // 3e moto A : accepté (3 commandées)
				numero() + ";" + d.codeA() + ";;");                // 4e moto A : quantité dépassée
		String url = "/api/v1/arrivages/" + id + "/colisage";

		// La simulation montre le rapport sans rien créer.
		admin.postFichier(url, "colisage.csv", fichier.getBytes(StandardCharsets.UTF_8), "simulation", "true").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.simulation").value(true)).andExpect(jsonPath("$.data.nb_importees").value(4)).andExpect(jsonPath("$.data.nb_rejetees").value(5));
		assertThat(jdbc.sql("SELECT count(*) FROM chassis WHERE arrivage_id = :a").param("a", id).query(Long.class).single()).isZero();

		admin.postFichier(url, "colisage.csv", fichier.getBytes(StandardCharsets.UTF_8)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.nb_lignes").value(9)).andExpect(jsonPath("$.data.nb_importees").value(4)).andExpect(jsonPath("$.data.nb_rejetees").value(5))
				.andExpect(jsonPath("$.data.rejets[?(@.ligne==4)].motif").value(hasItem("doublon dans le fichier")))
				.andExpect(jsonPath("$.data.rejets[?(@.ligne==5)].motif").value(hasItem(containsString("format"))))
				.andExpect(jsonPath("$.data.rejets[?(@.ligne==6)].motif").value(hasItem(containsString("code article inconnu"))))
				.andExpect(jsonPath("$.data.rejets[?(@.ligne==7)].motif").value(hasItem("numéro de châssis manquant")))
				.andExpect(jsonPath("$.data.rejets[?(@.ligne==11)].motif").value(hasItem(containsString("quantité commandée dépassée"))));

		// Les motos annoncées sont « en transit fournisseur », rattachées à l'arrivage, hors stock et sans emplacement.
		assertThat(jdbc.sql("SELECT count(*) FROM chassis WHERE arrivage_id = :a AND statut = 'EN_TRANSIT_FOURNISSEUR' AND emplacement_id IS NULL AND date_entree IS NULL")
				.param("a", id).query(Long.class).single()).isEqualTo(4);
		assertThat(jdbc.sql("SELECT numero_moteur || '/' || couleur FROM chassis WHERE numero = :n").param("n", n1).query(String.class).single()).isEqualTo("MOT-1/Rouge");
		assertThat(jdbc.sql("SELECT count(*) FROM chassis WHERE numero = :n").param("n", n2).query(Long.class).single()).isEqualTo(1);
		admin.get("/api/v1/arrivages/" + id).andExpect(jsonPath("$.data.nb_chassis").value(4)).andExpect(jsonPath("$.data.chassis", hasSize(4)))
				.andExpect(jsonPath("$.data.imports", hasSize(1))).andExpect(jsonPath("$.data.imports[0].nb_rejetees").value(5)).andExpect(jsonPath("$.data.imports[0].rejets", hasSize(5)));

		// Un second import du même fichier : tout est refusé comme déjà enregistré, avec l'endroit où se trouve la moto.
		admin.postFichier(url, "colisage.csv", fichier.getBytes(StandardCharsets.UTF_8)).andExpect(jsonPath("$.data.nb_importees").value(0))
				.andExpect(jsonPath("$.data.rejets[?(@.ligne==2)].motif").value(hasItem(containsString("déjà enregistré : En transit fournisseur"))));
		assertThat(jdbc.sql("SELECT count(*) FROM chassis WHERE arrivage_id = :a").param("a", id).query(Long.class).single()).isEqualTo(4);
	}

	@Test
	void leColisageAccepteLesExportsExcelFrancaisCommeLesFichiersXlsxEtRefuseLeReste() throws Exception {
		ClientHttp admin = comptes().admin();
		Dossier d = dossier(true);
		Long id = creerArrivage(admin, d, bl());
		String url = "/api/v1/arrivages/" + id + "/colisage";

		// CSV d'Excel français : séparateur « ; », accents, encodage Windows-1252, en-têtes libres.
		String n1 = numero();
		byte[] windows = csv("N° châssis;Modèle;Couleur", n1 + ";" + d.codeA() + ";Vert foncé é").getBytes("windows-1252");
		admin.postFichier(url, "export.csv", windows).andExpect(jsonPath("$.data.nb_importees").value(1));
		assertThat(jdbc.sql("SELECT couleur FROM chassis WHERE numero = :n").param("n", n1).query(String.class).single()).isEqualTo("Vert foncé é");
		// Séparateur virgule avec guillemets et BOM.
		String n2 = numero();
		byte[] virgule = ("﻿numero_chassis,code_article,couleur\n\"" + n2 + "\",\"" + d.codeA() + "\",\"Gris, mat\"\n").getBytes(StandardCharsets.UTF_8);
		admin.postFichier(url, "virgule.csv", virgule).andExpect(jsonPath("$.data.nb_importees").value(1));
		assertThat(jdbc.sql("SELECT couleur FROM chassis WHERE numero = :n").param("n", n2).query(String.class).single()).isEqualTo("Gris, mat");

		// Classeur .xlsx : première feuille, première ligne en en-tête.
		String n3 = numero();
		ByteArrayOutputStream sortie = new ByteArrayOutputStream();
		try (XSSFWorkbook classeur = new XSSFWorkbook()) {
			Sheet feuille = classeur.createSheet("Colisage");
			Row entete = feuille.createRow(0);
			entete.createCell(0).setCellValue("Numero chassis");
			entete.createCell(1).setCellValue("Code article");
			Row ligne = feuille.createRow(1);
			ligne.createCell(0).setCellValue(n3);
			ligne.createCell(1).setCellValue(d.codeB());
			classeur.write(sortie);
		}
		admin.postFichier(url, "colisage.xlsx", sortie.toByteArray()).andExpect(jsonPath("$.data.nb_importees").value(1));
		assertThat(jdbc.sql("SELECT statut FROM chassis WHERE numero = :n").param("n", n3).query(String.class).single()).isEqualTo("EN_TRANSIT_FOURNISSEUR");

		admin.postFichier(url, "colisage.pdf", "x".getBytes()).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.fichier[0]").exists());
		admin.postFichier(url, "colisage.xlsx", "pas un classeur".getBytes()).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.fichier[0]").value(containsString("illisible")));
		admin.postFichier(url, "colonnes.csv", csv("a;b", "1;2").getBytes()).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.fichier[0]").value(containsString("numero_chassis")));
		admin.postFichier(url, "vide.csv", new byte[0]).andExpect(status().isUnprocessableEntity());
	}

	@Test
	void leDedouanementTermineFaitPasserLesChassisAReceptionnerEtPrevientLeMagasin() throws Exception {
		ComptesDeTest c = comptes();
		ClientHttp admin = c.admin();
		Dossier d = dossier(true);
		String bl = unique("BL");
		Long id = creerArrivage(admin, d, "{\"numero_bl\":\"" + bl + "\",\"date_arrivee_reelle\":\"" + LocalDate.now() + "\"}");
		String n1 = numero();
		String n2 = numero();
		admin.postFichier("/api/v1/arrivages/" + id + "/colisage", "c.csv", csv("numero_chassis;code_article", n1 + ";" + d.codeA(), n2 + ";" + d.codeB()).getBytes()).andExpect(status().isOk());
		String email = c.utilisateurActif("magasin");
		long avant = jdbc.sql("SELECT count(*) FROM notifications WHERE destinataire_id = :u AND type = 'arrivage.a_receptionner'").param("u", c.id(email)).query(Long.class).single();

		admin.post("/api/v1/arrivages/" + id + "/douane", "{}").andExpect(status().isOk());
		assertThat(jdbc.sql("SELECT count(*) FROM chassis WHERE arrivage_id = :a AND statut = 'EN_TRANSIT_FOURNISSEUR'").param("a", id).query(Long.class).single()).isEqualTo(2);
		admin.post("/api/v1/arrivages/" + id + "/douane", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.data.chassis[0].statut").value("A_RECEPTIONNER"))
				.andExpect(jsonPath("$.data.chassis[0].statut_libelle").value("À réceptionner"));

		assertThat(jdbc.sql("SELECT count(*) FROM chassis WHERE arrivage_id = :a AND statut = 'A_RECEPTIONNER' AND emplacement_id IS NULL").param("a", id).query(Long.class).single()).isEqualTo(2);
		assertThat(jdbc.sql("SELECT count(*) FROM mouvements_stock m JOIN chassis c ON c.id = m.chassis_id WHERE c.arrivage_id = :a AND m.type = 'ARRIVAGE_A_RECEPTIONNER'")
				.param("a", id).query(Long.class).single()).isEqualTo(2);
		assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE destinataire_id = :u AND type = 'arrivage.a_receptionner'").param("u", c.id(email)).query(Long.class).single()).isEqualTo(avant + 1);
		// Le colisage ne s'importe plus une fois l'arrivage dédouané.
		admin.postFichier("/api/v1/arrivages/" + id + "/colisage", "c.csv", csv("numero_chassis;code_article", numero() + ";" + d.codeA()).getBytes())
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("IMPORT_IMPOSSIBLE"));
		c.connecter(email).postFichier("/api/v1/arrivages/" + id + "/colisage", "c.csv", "x".getBytes()).andExpect(status().isForbidden());
	}

	@Test
	void laQuantiteCommandeeSeCumuleSurLesArrivagesDUneMemeCommande() throws Exception {
		ClientHttp admin = comptes().admin();
		Dossier d = dossier(true);
		Long premier = creerArrivage(admin, d, bl());
		Long second = creerArrivage(admin, d, bl());
		admin.postFichier("/api/v1/arrivages/" + premier + "/colisage", "a.csv",
				csv("numero_chassis;code_article", numero() + ";" + d.codeB(), numero() + ";" + d.codeB()).getBytes()).andExpect(jsonPath("$.data.nb_importees").value(2));
		// Les 2 motos B commandées sont déjà annoncées par le premier arrivage.
		admin.postFichier("/api/v1/arrivages/" + second + "/colisage", "b.csv", csv("numero_chassis;code_article", numero() + ";" + d.codeB()).getBytes())
				.andExpect(jsonPath("$.data.nb_importees").value(0)).andExpect(jsonPath("$.data.rejets[0].motif").value(containsString("quantité commandée dépassée")));
	}

}
