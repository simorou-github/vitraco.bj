package bj.vitraco.api.adm;

import bj.vitraco.api.TestcontainersConfiguration;
import bj.vitraco.api.TestcontainersConfiguration.FacturesSimulees;
import bj.vitraco.api.cat.chassis.ChassisService;
import bj.vitraco.api.cat.chassis.CreationChassis;
import bj.vitraco.api.cat.chassis.StatutChassis;
import bj.vitraco.api.common.ErreurMetierException;
import bj.vitraco.api.common.ErreurValidationException;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ChassisTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	ChassisService service;
	@Autowired
	TransactionTemplate transaction;
	@Autowired
	FacturesSimulees factures;

	private ComptesDeTest comptes() {
		return new ComptesDeTest(mvc, jdbc);
	}

	private static String unique(String prefixe) {
		return prefixe + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
	}

	/** Un modèle au format de châssis « LWB + 14 caractères », et deux emplacements. */
	private record Decor(Long modeleId, String codeModele, Long magasinA, Long magasinB, String prefixe) {
	}

	private Decor decor() throws Exception {
		ClientHttp admin = comptes().admin();
		String code = unique("CH");
		admin.post("/api/v1/modeles", "{\"marque\":\"Honda\",\"modele\":\"Test\",\"code_article\":\"%s\",\"format_chassis\":\"^LWB[A-Z0-9]{9}$\",\"prix_vente\":1000000,\"prix_minimum\":900000}"
				.formatted(code)).andExpect(status().isCreated());
		Long modele = jdbc.sql("SELECT id FROM modeles WHERE code_article = :c").param("c", code).query(Long.class).single();
		Long a = emplacement(admin, "MAGASIN");
		Long b = emplacement(admin, "MAGASIN");
		return new Decor(modele, code, a, b, "LWB");
	}

	private Long emplacement(ClientHttp admin, String type) throws Exception {
		String code = unique("E");
		admin.post("/api/v1/emplacements", "{\"code\":\"%s\",\"nom\":\"Magasin %s\",\"type\":\"%s\",\"responsable_nom\":\"R\",\"email\":\"r@vitraco.bj\",\"telephone\":\"1\"}"
				.formatted(code, code, type)).andExpect(status().isCreated());
		return jdbc.sql("SELECT id FROM emplacements WHERE code = :c").param("c", code).query(Long.class).single();
	}

	private String numero(Decor d) {
		return d.prefixe() + UUID.randomUUID().toString().replace("-", "").substring(0, 9).toUpperCase();
	}

	private Long creer(Decor d, String numero, StatutChassis statut, Long emplacement) {
		return transaction.execute(s -> service.creer(new CreationChassis(numero, d.modeleId(), statut, emplacement, null, null, 800_000L,
				null, "Rouge", "TEST", 1L)).getId());
	}

	@Test
	void unChassisSeNormaliseSeControleEtSeJournalise() {
		Decor d;
		try {
			d = decor();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		String brut = numero(d);
		String saisie = brut.substring(0, 3).toLowerCase() + " " + brut.substring(3, 6) + "-" + brut.substring(6);
		Long id = creer(d, saisie, StatutChassis.DISPONIBLE, d.magasinA());

		assertThat(jdbc.sql("SELECT numero FROM chassis WHERE id = :i").param("i", id).query(String.class).single()).isEqualTo(brut);
		assertThat(jdbc.sql("SELECT count(*) FROM mouvements_stock WHERE chassis_id = :i AND type = 'CREATION' AND statut_apres = 'DISPONIBLE'")
				.param("i", id).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT date_entree IS NOT NULL FROM chassis WHERE id = :i").param("i", id).query(Boolean.class).single()).isTrue();
		assertThat(jdbc.sql("SELECT count(*) FROM journal_audit WHERE action = 'chassis.cree' AND objet_id = :i").param("i", String.valueOf(id))
				.query(Long.class).single()).isEqualTo(1);
		boolean retrouve = Boolean.TRUE.equals(transaction.execute(s -> service.parNumero(saisie.toUpperCase()).isPresent()));
		assertThat(retrouve).isTrue();
	}

	@Test
	void unDoublonEstRefuseAvecLEmplacementDuChassisExistant() throws Exception {
		Decor d = decor();
		String n = numero(d);
		creer(d, n, StatutChassis.DISPONIBLE, d.magasinA());
		String nomMagasin = jdbc.sql("SELECT nom FROM emplacements WHERE id = :i").param("i", d.magasinA()).query(String.class).single();

		// Même numéro écrit autrement : toujours un doublon, et on dit où est la moto.
		assertThatThrownBy(() -> creer(d, n.toLowerCase().substring(0, 4) + "-" + n.substring(4), StatutChassis.EN_TRANSIT_FOURNISSEUR, null))
				.isInstanceOfSatisfying(ErreurMetierException.class, e -> {
					assertThat(e.getCode()).isEqualTo("CHASSIS_DEJA_EXISTANT");
					assertThat(e.getMessage()).contains("Disponible").contains(nomMagasin);
				});
		assertThat(jdbc.sql("SELECT count(*) FROM chassis WHERE numero = :n").param("n", n).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void leFormatDuModeleLesStatutsInitiauxEtLEmplacementSontControles() throws Exception {
		Decor d = decor();
		assertThatThrownBy(() -> creer(d, "XYZ123456789", StatutChassis.DISPONIBLE, d.magasinA()))
				.isInstanceOfSatisfying(ErreurValidationException.class, e -> assertThat(e.getMessage()).contains("format"));
		assertThatThrownBy(() -> creer(d, "LWB@@@###", StatutChassis.DISPONIBLE, d.magasinA())).isInstanceOf(ErreurValidationException.class);
		assertThatThrownBy(() -> creer(d, "AB", StatutChassis.DISPONIBLE, d.magasinA())).isInstanceOf(ErreurValidationException.class);

		assertThatThrownBy(() -> creer(d, numero(d), StatutChassis.LIVREE, d.magasinA()))
				.isInstanceOfSatisfying(ErreurMetierException.class, e -> assertThat(e.getCode()).isEqualTo("STATUT_INITIAL_INVALIDE"));
		assertThatThrownBy(() -> creer(d, numero(d), StatutChassis.EN_TRANSIT_FOURNISSEUR, d.magasinA()))
				.isInstanceOfSatisfying(ErreurMetierException.class, e -> assertThat(e.getCode()).isEqualTo("EMPLACEMENT_INTERDIT"));
		assertThatThrownBy(() -> creer(d, numero(d), StatutChassis.DISPONIBLE, null)).isInstanceOf(ErreurValidationException.class);
		assertThatThrownBy(() -> creer(d, numero(d), StatutChassis.DISPONIBLE, 999_999L)).isInstanceOf(ErreurValidationException.class);

		Long enImport = creer(d, numero(d), StatutChassis.EN_TRANSIT_FOURNISSEUR, null);
		assertThat(jdbc.sql("SELECT emplacement_id IS NULL AND date_entree IS NULL FROM chassis WHERE id = :i").param("i", enImport).query(Boolean.class).single()).isTrue();
	}

	@Test
	void labaseRefuseLesEtatsIncoherentsEtLesMouvementsSontEnAjoutSeul() throws Exception {
		Decor d = decor();
		Long id = creer(d, numero(d), StatutChassis.DISPONIBLE, d.magasinA());

		// Un châssis en stock sans emplacement, ou un numéro non normalisé : impossible, même en SQL direct.
		assertThatThrownBy(() -> jdbc.sql("UPDATE chassis SET emplacement_id = NULL WHERE id = :i").param("i", id).update())
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("UPDATE chassis SET numero = 'abc-123' WHERE id = :i").param("i", id).update())
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("UPDATE chassis SET statut = 'INCONNU' WHERE id = :i").param("i", id).update())
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("UPDATE mouvements_stock SET motif = 'x' WHERE chassis_id = :i").param("i", id).update())
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("DELETE FROM mouvements_stock WHERE chassis_id = :i").param("i", id).update())
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void laListeSeFiltreSeCherchePartielEtLesAnciennetesSeCalculent() throws Exception {
		Decor d = decor();
		ClientHttp admin = comptes().admin();
		String nA = numero(d);
		String nB = numero(d);
		Long idA = creer(d, nA, StatutChassis.DISPONIBLE, d.magasinA());
		Long idB = creer(d, nB, StatutChassis.DISPONIBLE, d.magasinB());
		creer(d, numero(d), StatutChassis.EN_TRANSIT_FOURNISSEUR, null);
		jdbc.sql("UPDATE chassis SET statut = 'RESERVEE' WHERE id = :i").param("i", idB).update();
		jdbc.sql("UPDATE chassis SET date_entree = current_date - 40 WHERE id = :i").param("i", idA).update();
		String parModele = "modele_id=" + d.modeleId();

		admin.get("/api/v1/chassis?" + parModele).andExpect(status().isOk()).andExpect(jsonPath("$.meta.total").value(3));
		admin.get("/api/v1/chassis?" + parModele + "&statut=RESERVEE").andExpect(jsonPath("$.data", hasSize(1))).andExpect(jsonPath("$.data[0].numero").value(nB));
		admin.get("/api/v1/chassis?" + parModele + "&emplacement_id=" + d.magasinA()).andExpect(jsonPath("$.data", hasSize(1)))
				.andExpect(jsonPath("$.data[0].emplacement.id").value(d.magasinA().intValue()));
		admin.get("/api/v1/chassis?" + parModele + "&anciennete_min=30").andExpect(jsonPath("$.data", hasSize(1)))
				.andExpect(jsonPath("$.data[0].anciennete_jours").value(40));
		admin.get("/api/v1/chassis?" + parModele + "&statut=EN_TRANSIT_FOURNISSEUR").andExpect(jsonPath("$.data", hasSize(1)))
				.andExpect(jsonPath("$.data[0].emplacement").doesNotExist());
		// Recherche partielle, insensible à la casse et aux séparateurs.
		admin.get("/api/v1/chassis?search=" + (nA.substring(0, 5) + "-" + nA.substring(5)).toLowerCase()).andExpect(jsonPath("$.data[*].numero").value(hasItem(nA)));
		admin.get("/api/v1/chassis?statut=NIMPORTE").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PARAMETRE_INVALIDE"));
	}

	@Test
	void chacunNeVoitQueLesChassisDeSesEmplacements() throws Exception {
		Decor d = decor();
		ComptesDeTest c = comptes();
		Long idA = creer(d, numero(d), StatutChassis.DISPONIBLE, d.magasinA());
		Long idB = creer(d, numero(d), StatutChassis.DISPONIBLE, d.magasinB());
		Long idImport = creer(d, numero(d), StatutChassis.EN_TRANSIT_FOURNISSEUR, null);
		ClientHttp magasinier = c.connecter(c.utilisateurActif("magasin", d.magasinA()));

		magasinier.get("/api/v1/chassis?modele_id=" + d.modeleId()).andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(1)))
				.andExpect(jsonPath("$.data[0].id").value(idA.intValue()));
		magasinier.get("/api/v1/chassis/" + idA).andExpect(status().isOk());
		// Hors périmètre, un châssis est introuvable : on ne révèle même pas son existence.
		magasinier.get("/api/v1/chassis/" + idB).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CHASSIS_INTROUVABLE"));
		magasinier.get("/api/v1/chassis/" + idImport).andExpect(status().isNotFound());
		magasinier.get("/api/v1/chassis?modele_id=" + d.modeleId() + "&emplacement_id=" + d.magasinB()).andExpect(jsonPath("$.data", hasSize(0)));

		// Sans aucun emplacement attribué, aucune donnée.
		ClientHttp sansPerimetre = c.connecter(c.utilisateurActif("magasin"));
		sansPerimetre.get("/api/v1/chassis?modele_id=" + d.modeleId()).andExpect(jsonPath("$.data", hasSize(0)));
		new ClientHttp(mvc).get("/api/v1/chassis").andExpect(status().isUnauthorized());
	}

	@Test
	void leCoutDeRevientEstMasqueSansLaPermissionEtLaFicheRaconteLHistoire() throws Exception {
		Decor d = decor();
		ComptesDeTest c = comptes();
		String n = numero(d);
		Long id = creer(d, n, StatutChassis.DISPONIBLE, d.magasinA());

		comptes().admin().get("/api/v1/chassis/" + id).andExpect(status().isOk()).andExpect(jsonPath("$.data.chassis.cout_revient").doesNotExist())
				.andExpect(jsonPath("$.data.chassis.cout_visible").value(false)).andExpect(jsonPath("$.data.chassis.numero").value(n))
				.andExpect(jsonPath("$.data.chassis.statut").value("DISPONIBLE")).andExpect(jsonPath("$.data.chassis.statut_libelle").value("Disponible"))
				.andExpect(jsonPath("$.data.chassis.modele").value("Honda Test")).andExpect(jsonPath("$.data.historique", hasSize(1)))
				.andExpect(jsonPath("$.data.historique[0].type").value("CREATION")).andExpect(jsonPath("$.data.historique[0].statut_apres").value("DISPONIBLE"))
				.andExpect(jsonPath("$.data.historique[0].emplacement_destination_id").value(d.magasinA().intValue()))
				.andExpect(jsonPath("$.data.corrections", hasSize(0)));
		ClientHttp acheteur = c.connecter(c.utilisateurActif(c.roleGlobal("stock.voir", "prix_achat.voir")));
		acheteur.get("/api/v1/chassis/" + id).andExpect(jsonPath("$.data.chassis.cout_revient").value(800000)).andExpect(jsonPath("$.data.chassis.cout_visible").value(true));
	}

	@Test
	void lExportCsvDemandeSonDroitSeCommeSansFormulesEtEstJournalise() throws Exception {
		Decor d = decor();
		ComptesDeTest c = comptes();
		String n = numero(d);
		creer(d, n, StatutChassis.DISPONIBLE, d.magasinA());

		comptes().admin().get("/api/v1/chassis/export").andExpect(status().isForbidden());
		ClientHttp direction = c.connecter(c.utilisateurActif(c.roleGlobal("stock.voir", "stock.exporter", "prix_achat.voir")));
		direction.get("/api/v1/chassis/export?modele_id=" + d.modeleId()).andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("text/csv"))
				.andExpect(content().string(containsString("Numéro;Modèle;Code article;Statut;Emplacement;Date d'entrée;Ancienneté (jours);Couleur;N° moteur;Coût de revient")))
				.andExpect(content().string(containsString(n + ";Honda Test;" + d.codeModele() + ";Disponible;")))
				.andExpect(content().string(containsString("800000")));
		assertThat(jdbc.sql("SELECT count(*) FROM journal_audit WHERE action = 'chassis.exporte'").query(Long.class).single()).isGreaterThanOrEqualTo(1);
		// Sans la permission « prix d'achat », la colonne du coût n'existe pas dans le fichier.
		ClientHttp sansCout = c.connecter(c.utilisateurActif(c.roleGlobal("stock.voir", "stock.exporter")));
		sansCout.get("/api/v1/chassis/export?modele_id=" + d.modeleId()).andExpect(content().string(org.hamcrest.Matchers.not(containsString("Coût de revient"))))
				.andExpect(content().string(org.hamcrest.Matchers.not(containsString("800000"))));
	}

	private record Equipe(ClientHttp demandeur, ClientHttp validateur, String emailMagasinier) {
	}

	private Equipe equipe() throws Exception {
		ComptesDeTest c = comptes();
		String role = c.roleGlobal("stock.voir", "stock.modifier");
		return new Equipe(c.connecter(c.utilisateurActif(role)), c.connecter(c.utilisateurActif(role)), c.utilisateurActif("magasin"));
	}

	@Test
	void uneCorrectionDeNumeroExigeUnMotifEtLaValidationDUnSecondUtilisateur() throws Exception {
		Decor d = decor();
		Equipe e = equipe();
		ComptesDeTest c = comptes();
		String ancien = numero(d);
		String nouveau = numero(d);
		Long id = creer(d, ancien, StatutChassis.DISPONIBLE, d.magasinA());
		Long magasinier = c.id(e.emailMagasinier());
		long avant = jdbc.sql("SELECT count(*) FROM notifications WHERE destinataire_id = :u AND type = 'chassis.correction_demandee'").param("u", magasinier).query(Long.class).single();

		e.demandeur().post("/api/v1/chassis/" + id + "/corrections", "{\"nouveau_numero\":\"%s\",\"motif\":\"\"}".formatted(nouveau))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.motif").exists());
		String reponse = e.demandeur().post("/api/v1/chassis/" + id + "/corrections",
						"{\"nouveau_numero\":\"%s\",\"motif\":\"Deux caractères inversés à la saisie\"}".formatted(nouveau.toLowerCase()))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.data.statut").value("DEMANDEE")).andExpect(jsonPath("$.data.nouveau_numero").value(nouveau))
				.andReturn().getResponse().getContentAsString();
		Long correction = Long.valueOf(com.jayway.jsonpath.JsonPath.read(reponse, "$.data.id").toString());
		assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE destinataire_id = :u AND type = 'chassis.correction_demandee'").param("u", magasinier).query(Long.class).single())
				.isEqualTo(avant + 1);
		// Le numéro n'a pas bougé tant que personne ne l'a validé ; une seule demande à la fois.
		assertThat(jdbc.sql("SELECT numero FROM chassis WHERE id = :i").param("i", id).query(String.class).single()).isEqualTo(ancien);
		e.demandeur().post("/api/v1/chassis/" + id + "/corrections", "{\"nouveau_numero\":\"%s\",\"motif\":\"Autre\"}".formatted(numero(d)))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CORRECTION_DEJA_DEMANDEE"));

		// Le demandeur ne décide pas de sa propre demande.
		e.demandeur().post("/api/v1/chassis/corrections/" + correction + "/valider", "{}").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("UTILISATEUR_IDENTIQUE"));
		e.demandeur().post("/api/v1/chassis/corrections/" + correction + "/refuser", "{\"motif\":\"non\"}").andExpect(status().isForbidden());

		e.validateur().post("/api/v1/chassis/corrections/" + correction + "/valider", "{}").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.statut").value("VALIDEE")).andExpect(jsonPath("$.data.ancien_numero").value(ancien));
		assertThat(jdbc.sql("SELECT numero FROM chassis WHERE id = :i").param("i", id).query(String.class).single()).isEqualTo(nouveau);
		e.validateur().post("/api/v1/chassis/corrections/" + correction + "/valider", "{}").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("CORRECTION_DEJA_TRAITEE"));

		// L'ancien numéro reste recherchable, la fiche raconte la correction avec son auteur, et le demandeur est prévenu.
		e.validateur().get("/api/v1/chassis?search=" + ancien).andExpect(jsonPath("$.data[*].numero").value(hasItem(nouveau)));
		e.validateur().get("/api/v1/chassis/" + id).andExpect(jsonPath("$.data.historique[1].type").value("CORRECTION_NUMERO"))
				.andExpect(jsonPath("$.data.historique[1].motif").value(containsString(ancien))).andExpect(jsonPath("$.data.corrections[0].ancien_numero").value(ancien))
				.andExpect(jsonPath("$.data.corrections[0].decide_par").exists());
		assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE type = 'chassis.correction_decidee' AND message LIKE :m").param("m", "%" + nouveau + "%")
				.query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT count(*) FROM journal_audit WHERE action = 'chassis.correction_validee' AND objet_id = :i").param("i", String.valueOf(id))
				.query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void uneCorrectionRefuseeLaisseLeNumeroEtLesNumerosInvalidesSontRefuses() throws Exception {
		Decor d = decor();
		Equipe e = equipe();
		String ancien = numero(d);
		Long id = creer(d, ancien, StatutChassis.DISPONIBLE, d.magasinA());
		String autre = numero(d);
		creer(d, autre, StatutChassis.DISPONIBLE, d.magasinB());

		String modele = "{\"nouveau_numero\":\"%s\",\"motif\":\"Erreur de saisie\"}";
		e.demandeur().post("/api/v1/chassis/" + id + "/corrections", modele.formatted(autre)).andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("CHASSIS_DEJA_EXISTANT"));
		e.demandeur().post("/api/v1/chassis/" + id + "/corrections", modele.formatted("MAUVAISFORMAT1")).andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors.numero[0]").exists());
		e.demandeur().post("/api/v1/chassis/" + id + "/corrections", modele.formatted(ancien)).andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.errors.nouveau_numero[0]").exists());

		String reponse = e.demandeur().post("/api/v1/chassis/" + id + "/corrections", modele.formatted(numero(d))).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		Long correction = Long.valueOf(com.jayway.jsonpath.JsonPath.read(reponse, "$.data.id").toString());
		e.validateur().post("/api/v1/chassis/corrections/" + correction + "/refuser", "{}").andExpect(status().isUnprocessableEntity());
		e.validateur().post("/api/v1/chassis/corrections/" + correction + "/refuser", "{\"motif\":\"Le numéro d'origine est correct\"}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.statut").value("REFUSEE")).andExpect(jsonPath("$.data.motif_refus").exists());
		assertThat(jdbc.sql("SELECT numero FROM chassis WHERE id = :i").param("i", id).query(String.class).single()).isEqualTo(ancien);
		// Une demande refusée n'en bloque pas une nouvelle.
		e.demandeur().post("/api/v1/chassis/" + id + "/corrections", modele.formatted(numero(d))).andExpect(status().isCreated());
	}

	@Test
	void unChassisFactureNePeutPlusEtreCorrigeMemeSiLaDemandeEtaitAnterieure() throws Exception {
		Decor d = decor();
		Equipe e = equipe();
		Long id = creer(d, numero(d), StatutChassis.DISPONIBLE, d.magasinA());
		Long facture = creer(d, numero(d), StatutChassis.DISPONIBLE, d.magasinA());
		factures.normaliser(facture);

		String modele = "{\"nouveau_numero\":\"%s\",\"motif\":\"Erreur de saisie\"}";
		e.demandeur().post("/api/v1/chassis/" + facture + "/corrections", modele.formatted(numero(d))).andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("CORRECTION_IMPOSSIBLE")).andExpect(jsonPath("$.message").value(containsString("facture normalisée")));

		// La demande est faite avant la facturation, la validation après : refusée.
		String reponse = e.demandeur().post("/api/v1/chassis/" + id + "/corrections", modele.formatted(numero(d))).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		Long correction = Long.valueOf(com.jayway.jsonpath.JsonPath.read(reponse, "$.data.id").toString());
		factures.normaliser(id);
		e.validateur().post("/api/v1/chassis/corrections/" + correction + "/valider", "{}").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("CORRECTION_IMPOSSIBLE"));
	}

	@Test
	void seulsLesProfilsAutorisesDemandentUneCorrection() throws Exception {
		Decor d = decor();
		ComptesDeTest c = comptes();
		Long id = creer(d, numero(d), StatutChassis.DISPONIBLE, d.magasinA());
		ClientHttp boutique = c.connecter(c.utilisateurActif("commercial", d.magasinA()));
		boutique.post("/api/v1/chassis/" + id + "/corrections", "{\"nouveau_numero\":\"%s\",\"motif\":\"x\"}".formatted(numero(d))).andExpect(status().isForbidden());
		boutique.post("/api/v1/chassis/corrections/1/valider", "{}").andExpect(status().isForbidden());
		comptes().admin().post("/api/v1/chassis/99999/corrections", "{\"nouveau_numero\":\"%s\",\"motif\":\"x\"}".formatted(numero(d))).andExpect(status().isForbidden());
	}

}
