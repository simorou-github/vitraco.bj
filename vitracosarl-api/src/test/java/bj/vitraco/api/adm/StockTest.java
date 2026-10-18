package bj.vitraco.api.adm;

import bj.vitraco.api.TestcontainersConfiguration;
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
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Le stock se calcule sur les châssis (SRS §5.6) ; on ne le saisit jamais (EF-STK-04). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class StockTest {

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

	private Long emplacement(String type, String modeBoutique) {
		String code = unique("S");
		return jdbc.sql("INSERT INTO emplacements (code, nom, type, mode_boutique, actif) VALUES (:c, :n, :t, :m, true) RETURNING id")
				.param("c", code).param("n", "Lieu " + code).param("t", type).param("m", modeBoutique).query(Long.class).single();
	}

	private void chassis(Long modele, String statut, Long emplacement, int nombre) {
		for (int i = 0; i < nombre; i++) {
			jdbc.sql("INSERT INTO chassis (numero, modele_id, statut, emplacement_id, date_entree) VALUES (:n, :m, :s, :e, :d)")
					.param("n", "ST" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase()).param("m", modele).param("s", statut)
					.param("e", emplacement).param("d", emplacement == null ? null : LocalDate.now()).update();
		}
	}

	/**
	 * Magasin : 5 disponibles, 2 réservés, 1 en préparation, 1 bloqué, 1 livré (hors stock). Boutique : 3 disponibles.
	 * Dépôt revendeur : 2 en dépôt-vente. Transit interne : 1. Importation : 2 en transit fournisseur, 1 à réceptionner.
	 */
	private record Decor(Long modele, String code, Long magasin, Long boutique, Long depot, Long transit) {
	}

	private Decor decor() throws Exception {
		String code = unique("ST");
		comptes().admin().post("/api/v1/modeles", "{\"marque\":\"Honda\",\"modele\":\"Stock\",\"code_article\":\"%s\",\"prix_vente\":1000000,\"prix_minimum\":900000}".formatted(code))
				.andExpect(status().isCreated());
		Long modele = jdbc.sql("SELECT id FROM modeles WHERE code_article = :c").param("c", code).query(Long.class).single();
		Long magasin = emplacement("MAGASIN", null);
		Long boutique = emplacement("BOUTIQUE", "A");
		Long depot = emplacement("DEPOT_REVENDEUR", null);
		Long transit = jdbc.sql("SELECT id FROM emplacements WHERE code = 'TRANSIT'").query(Long.class).single();
		chassis(modele, "DISPONIBLE", magasin, 5);
		chassis(modele, "RESERVEE", magasin, 2);
		chassis(modele, "EN_PREPARATION", magasin, 1);
		chassis(modele, "BLOQUEE", magasin, 1);
		chassis(modele, "LIVREE", magasin, 1);
		chassis(modele, "DISPONIBLE", boutique, 3);
		chassis(modele, "DEPOT_VENTE", depot, 2);
		chassis(modele, "EN_TRANSIT_INTERNE", transit, 1);
		chassis(modele, "EN_TRANSIT_FOURNISSEUR", null, 2);
		chassis(modele, "A_RECEPTIONNER", null, 1);
		return new Decor(modele, code, magasin, boutique, depot, transit);
	}

	@Test
	void leStockSeCalculeSurLesChassisSelonLesDefinitionsDuSrs() throws Exception {
		ComptesDeTest c = comptes();
		Decor d = decor();
		ClientHttp direction = c.connecter(c.utilisateurActif(c.roleGlobal("stock.voir")));
		String filtre = "?modele_id=" + d.modele();

		// Vue consolidée : sites + transit interne + dépôt-vente, chaque moto comptée une fois ; l'importation est à part.
		direction.get("/api/v1/stock/synthese" + filtre).andExpect(status().isOk()).andExpect(jsonPath("$.data.consolide").value(true))
				.andExpect(jsonPath("$.data.total.physique").value(12)).andExpect(jsonPath("$.data.total.reserve").value(3))
				.andExpect(jsonPath("$.data.total.bloque").value(1)).andExpect(jsonPath("$.data.total.disponible").value(8))
				.andExpect(jsonPath("$.data.total.en_transit_interne").value(1)).andExpect(jsonPath("$.data.total.depot_vente").value(2))
				.andExpect(jsonPath("$.data.global").value(15))
				.andExpect(jsonPath("$.data.importation.en_transit_fournisseur").value(2)).andExpect(jsonPath("$.data.importation.a_receptionner").value(1));
		// Par emplacement : physique − réservé − bloqué = disponible (US-040 : 9 physiques dont 3 réservées et 1 bloquée → 5).
		direction.get("/api/v1/stock/synthese" + filtre).andExpect(jsonPath("$.data.emplacements[?(@.id==%d)].indicateurs.physique".formatted(d.magasin())).value(hasItem(9)))
				.andExpect(jsonPath("$.data.emplacements[?(@.id==%d)].indicateurs.disponible".formatted(d.magasin())).value(hasItem(5)))
				.andExpect(jsonPath("$.data.emplacements[?(@.id==%d)].indicateurs.reserve".formatted(d.magasin())).value(hasItem(3)))
				.andExpect(jsonPath("$.data.emplacements[?(@.id==%d)].indicateurs.bloque".formatted(d.magasin())).value(hasItem(1)))
				.andExpect(jsonPath("$.data.emplacements[?(@.id==%d)].indicateurs.physique".formatted(d.boutique())).value(hasItem(3)))
				.andExpect(jsonPath("$.data.emplacements[?(@.id==%d)].indicateurs.depot_vente".formatted(d.depot())).value(hasItem(2)))
				.andExpect(jsonPath("$.data.emplacements[?(@.id==%d)].indicateurs.en_transit_interne".formatted(d.transit())).value(hasItem(1)));
		// Seules les boutiques, ou un seul modèle : l'importation n'est montrée que dans la vue complète.
		direction.get("/api/v1/stock/synthese" + filtre + "&type=BOUTIQUE").andExpect(jsonPath("$.data.total.physique").value(3))
				.andExpect(jsonPath("$.data.importation.a_receptionner").value(0));

		// Par modèle, avec les motos en cours d'importation, puis restreint à un emplacement (EF-STK-03).
		direction.get("/api/v1/stock/modeles?search=" + d.code()).andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(1)))
				.andExpect(jsonPath("$.data[0].indicateurs.physique").value(12)).andExpect(jsonPath("$.data[0].importation.en_transit_fournisseur").value(2));
		direction.get("/api/v1/stock/modeles?search=" + d.code() + "&emplacement_id=" + d.magasin()).andExpect(jsonPath("$.data[0].indicateurs.physique").value(9))
				.andExpect(jsonPath("$.data[0].importation.en_transit_fournisseur").value(0));
		direction.get("/api/v1/stock/modeles?search=" + d.code() + "&type=DEPOT_REVENDEUR").andExpect(jsonPath("$.data[0].indicateurs.depot_vente").value(2));
		direction.get("/api/v1/stock/modeles?search=introuvable-" + UUID.randomUUID()).andExpect(jsonPath("$.data", hasSize(0)));
	}

	@Test
	void chaqueUtilisateurNeVoitQueLeStockDeSesEmplacements() throws Exception {
		ComptesDeTest c = comptes();
		Decor d = decor();
		ClientHttp magasinier = c.connecter(c.utilisateurActif("magasin", d.magasin()));
		String filtre = "?modele_id=" + d.modele();

		magasinier.get("/api/v1/stock/synthese" + filtre).andExpect(status().isOk()).andExpect(jsonPath("$.data.consolide").value(false))
				.andExpect(jsonPath("$.data.emplacements", hasSize(1))).andExpect(jsonPath("$.data.emplacements[0].id").value(d.magasin()))
				.andExpect(jsonPath("$.data.total.physique").value(9)).andExpect(jsonPath("$.data.total.depot_vente").value(0))
				.andExpect(jsonPath("$.data.importation.a_receptionner").value(0));
		magasinier.get("/api/v1/stock/modeles?search=" + d.code()).andExpect(jsonPath("$.data[0].indicateurs.physique").value(9));
		magasinier.get("/api/v1/stock/modeles?emplacement_id=" + d.boutique()).andExpect(status().isNotFound());

		// Aucun emplacement : rien à voir.
		ClientHttp sansLieu = c.connecter(c.utilisateurActif(c.role(false, "stock.voir")));
		sansLieu.get("/api/v1/stock/synthese").andExpect(status().isOk()).andExpect(jsonPath("$.data.emplacements", hasSize(0)));
		sansLieu.get("/api/v1/stock/mouvements").andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(0)));
		sansLieu.get("/api/v1/stock/bloques").andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(0)));
		new ClientHttp(mvc).get("/api/v1/stock/synthese").andExpect(status().isUnauthorized());
	}

	@Test
	void blocageEtDeblocageExigentUnDroitUnMotifEtUnePieceEtSontTraces() throws Exception {
		ComptesDeTest c = comptes();
		Decor d = decor();
		ClientHttp magasinier = c.connecter(c.utilisateurActif("magasin", d.magasin()));
		ClientHttp ailleurs = c.connecter(c.utilisateurActif("magasin", d.boutique()));
		ClientHttp lecteur = c.connecter(c.utilisateurActif(c.role(false, "stock.voir")));
		Long disponible = jdbc.sql("SELECT id FROM chassis WHERE modele_id = :m AND emplacement_id = :e AND statut = 'DISPONIBLE' LIMIT 1").param("m", d.modele()).param("e", d.magasin()).query(Long.class).single();
		Long reserve = jdbc.sql("SELECT id FROM chassis WHERE modele_id = :m AND statut = 'RESERVEE' LIMIT 1").param("m", d.modele()).query(Long.class).single();
		String url = "/api/v1/stock/chassis/" + disponible;

		lecteur.post(url + "/bloquer", "{\"motif\":\"Litige\",\"piece\":\"PV-1\"}").andExpect(status().isForbidden());
		ailleurs.post(url + "/bloquer", "{\"motif\":\"Litige\",\"piece\":\"PV-1\"}").andExpect(status().isNotFound());
		magasinier.post(url + "/bloquer", "{\"motif\":\"\",\"piece\":\"PV-1\"}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.motif[0]").exists());
		magasinier.post(url + "/bloquer", "{\"motif\":\"Litige\"}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.piece[0]").exists());
		// Une moto réservée est promise à un client : elle ne se bloque pas directement.
		magasinier.post("/api/v1/stock/chassis/" + reserve + "/bloquer", "{\"motif\":\"Litige\",\"piece\":\"PV-1\"}").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("CHASSIS_NON_BLOCABLE"));

		magasinier.post(url + "/bloquer", "{\"motif\":\"Cadre fissuré constaté au déchargement\",\"piece\":\"PV-2026-17\"}").andExpect(status().isOk());
		assertThat(jdbc.sql("SELECT statut FROM chassis WHERE id = :i").param("i", disponible).query(String.class).single()).isEqualTo("BLOQUEE");
		magasinier.post(url + "/bloquer", "{\"motif\":\"Encore\",\"piece\":\"PV-3\"}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CHASSIS_NON_BLOCABLE"));
		// Le stock bouge : une moto de moins disponible, une de plus bloquée, le physique reste identique.
		magasinier.get("/api/v1/stock/synthese?modele_id=" + d.modele()).andExpect(jsonPath("$.data.total.physique").value(9))
				.andExpect(jsonPath("$.data.total.bloque").value(2)).andExpect(jsonPath("$.data.total.disponible").value(4));
		assertThat(jdbc.sql("SELECT motif FROM mouvements_stock WHERE chassis_id = :i AND type = 'BLOCAGE'").param("i", disponible).query(String.class).single())
				.contains("Cadre fissuré").contains("PV-2026-17");
		assertThat(jdbc.sql("SELECT count(*) FROM journal_audit WHERE action = 'chassis.bloque' AND objet_id = :i").param("i", String.valueOf(disponible)).query(Long.class).single()).isEqualTo(1);

		// La liste des châssis bloqués dit pourquoi et depuis quand.
		magasinier.get("/api/v1/stock/bloques?search=" + jdbc.sql("SELECT numero FROM chassis WHERE id = :i").param("i", disponible).query(String.class).single())
				.andExpect(jsonPath("$.data", hasSize(1))).andExpect(jsonPath("$.data[0].motif").value(containsString("Cadre fissuré"))).andExpect(jsonPath("$.data[0].emplacement_id").value(d.magasin()));
		magasinier.get("/api/v1/stock/bloques").andExpect(jsonPath("$.meta.total").value(2));

		// Déblocage : motif obligatoire, droit spécifique, et seulement pour une moto bloquée.
		lecteur.post(url + "/debloquer", "{\"motif\":\"Réparée\"}").andExpect(status().isForbidden());
		magasinier.post(url + "/debloquer", "{}").andExpect(status().isUnprocessableEntity());
		magasinier.post(url + "/debloquer", "{\"motif\":\"Cadre remplacé sous garantie\"}").andExpect(status().isOk());
		magasinier.post(url + "/debloquer", "{\"motif\":\"Encore\"}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CHASSIS_NON_BLOQUE"));
		assertThat(jdbc.sql("SELECT statut FROM chassis WHERE id = :i").param("i", disponible).query(String.class).single()).isEqualTo("DISPONIBLE");
		assertThat(jdbc.sql("SELECT count(*) FROM mouvements_stock WHERE chassis_id = :i AND type IN ('BLOCAGE', 'DEBLOCAGE')").param("i", disponible).query(Long.class).single()).isEqualTo(2);
	}

	@Test
	void leJournalDesMouvementsSeFiltreEtRespecteLePerimetre() throws Exception {
		ComptesDeTest c = comptes();
		Decor d = decor();
		ClientHttp magasinier = c.connecter(c.utilisateurActif("magasin", d.magasin()));
		ClientHttp boutique = c.connecter(c.utilisateurActif("magasin", d.boutique()));
		ClientHttp direction = c.connecter(c.utilisateurActif(c.roleGlobal("stock.voir")));
		Long chassisId = jdbc.sql("SELECT id FROM chassis WHERE modele_id = :m AND emplacement_id = :e AND statut = 'DISPONIBLE' LIMIT 1").param("m", d.modele()).param("e", d.magasin()).query(Long.class).single();
		String numero = jdbc.sql("SELECT numero FROM chassis WHERE id = :i").param("i", chassisId).query(String.class).single();
		magasinier.post("/api/v1/stock/chassis/" + chassisId + "/bloquer", "{\"motif\":\"Litige client\",\"piece\":\"REF-9\"}").andExpect(status().isOk());
		magasinier.post("/api/v1/stock/chassis/" + chassisId + "/debloquer", "{\"motif\":\"Litige clos\"}").andExpect(status().isOk());

		magasinier.get("/api/v1/stock/mouvements?search=" + numero).andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(2)))
				.andExpect(jsonPath("$.data[0].type").value("DEBLOCAGE")).andExpect(jsonPath("$.data[1].type").value("BLOCAGE"))
				.andExpect(jsonPath("$.data[1].statut_avant").value("DISPONIBLE")).andExpect(jsonPath("$.data[1].statut_apres").value("BLOQUEE"))
				.andExpect(jsonPath("$.data[0].auteur").exists());
		magasinier.get("/api/v1/stock/mouvements?search=" + numero + "&type=BLOCAGE").andExpect(jsonPath("$.data", hasSize(1)));
		magasinier.get("/api/v1/stock/mouvements?search=" + numero + "&modele_id=" + d.modele() + "&emplacement_id=" + d.magasin()
				+ "&du=" + LocalDate.now() + "&au=" + LocalDate.now()).andExpect(jsonPath("$.data", hasSize(2)));
		magasinier.get("/api/v1/stock/mouvements?search=" + numero + "&du=" + LocalDate.now().plusDays(1)).andExpect(jsonPath("$.data", hasSize(0)));
		direction.get("/api/v1/stock/mouvements?search=" + numero).andExpect(jsonPath("$.data", hasSize(2)));
		// L'autre magasin ne voit pas les mouvements du premier.
		boutique.get("/api/v1/stock/mouvements?search=" + numero).andExpect(jsonPath("$.data", hasSize(0)));
	}

	@Test
	void unEmplacementQuiPorteDuStockNeSeDesactivePasEtUneBoutiqueNeChangePasDeMode() throws Exception {
		ComptesDeTest c = comptes();
		ClientHttp admin = c.admin();
		String code = unique("US");
		admin.post("/api/v1/emplacements", "{\"code\":\"%s\",\"nom\":\"Boutique %s\",\"type\":\"BOUTIQUE\",\"mode_boutique\":\"A\",\"responsable_nom\":\"R\",\"email\":\"r@vitraco.bj\",\"telephone\":\"1\"}"
				.formatted(code, code)).andExpect(status().isCreated());
		Long id = jdbc.sql("SELECT id FROM emplacements WHERE code = :c").param("c", code).query(Long.class).single();
		String codeModele = unique("US");
		admin.post("/api/v1/modeles", "{\"marque\":\"Honda\",\"modele\":\"Usage\",\"code_article\":\"%s\",\"prix_vente\":1000000,\"prix_minimum\":900000}".formatted(codeModele)).andExpect(status().isCreated());
		Long modele = jdbc.sql("SELECT id FROM modeles WHERE code_article = :c").param("c", codeModele).query(Long.class).single();
		chassis(modele, "DISPONIBLE", id, 1);

		// Avec une moto en stock : ni désactivation, ni changement de mode (EF-ADM-08, EF-ADM-09).
		admin.post("/api/v1/emplacements/" + id + "/desactiver", "{}").andExpect(status().isUnprocessableEntity());
		// Une fois la moto livrée, l'emplacement est libre.
		jdbc.sql("UPDATE chassis SET statut = 'LIVREE' WHERE emplacement_id = :e").param("e", id).update();
		admin.post("/api/v1/emplacements/" + id + "/desactiver", "{}").andExpect(status().isOk());
	}

	@Test
	void aucuneRouteNePermetDeModifierUneQuantiteDeStock() throws Exception {
		ClientHttp admin = comptes().admin();
		admin.put("/api/v1/stock/synthese", "{\"physique\":1000}").andExpect(status().is4xxClientError());
		admin.post("/api/v1/stock/synthese", "{\"physique\":1000}").andExpect(status().is4xxClientError());
		admin.delete("/api/v1/stock/mouvements/1").andExpect(status().is4xxClientError());
	}

}
