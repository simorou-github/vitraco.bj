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

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Le tableau de bord se découpe par domaine : Administration, Commercial, Management. Chacun a son droit d'accès. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TableauDeBordMetierTest {

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
		String code = unique("D");
		return jdbc.sql("INSERT INTO emplacements (code, nom, type, mode_boutique, actif) VALUES (:c, :n, :t, :m, true) RETURNING id")
				.param("c", code).param("n", "Lieu " + code).param("t", type).param("m", modeBoutique).query(Long.class).single();
	}

	private void chassis(Long modele, String statut, Long emplacement, int nombre, int joursEnStock, Long cout) {
		for (int i = 0; i < nombre; i++) {
			jdbc.sql("INSERT INTO chassis (numero, modele_id, statut, emplacement_id, date_entree, cout_revient) VALUES (:n, :m, :s, :e, :d, :c)")
					.param("n", "TB" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase()).param("m", modele).param("s", statut)
					.param("e", emplacement).param("d", LocalDate.now().minusDays(joursEnStock)).param("c", cout).update();
		}
	}

	private Long modele(String libelle, boolean avecPrix) throws Exception {
		String code = unique("TD");
		if (!avecPrix) {
			// L'API exige un prix à la création : un modèle sans prix s'obtient en base (données reprises, par exemple).
			return jdbc.sql("INSERT INTO modeles (marque, nom, code_article) VALUES ('Honda', :n, :c) RETURNING id").param("n", libelle).param("c", code).query(Long.class).single();
		}
		comptes().admin().post("/api/v1/modeles", "{\"marque\":\"Honda\",\"modele\":\"%s\",\"code_article\":\"%s\",\"prix_vente\":1500000,\"prix_minimum\":1400000}".formatted(libelle, code))
				.andExpect(status().isCreated());
		return jdbc.sql("SELECT id FROM modeles WHERE code_article = :c").param("c", code).query(Long.class).single();
	}

	@Test
	void leDomaineCommercialMontreCeQuiSeVendDansLePerimetreDeLaPersonne() throws Exception {
		ComptesDeTest c = comptes();
		Long magasin = emplacement("MAGASIN", null);
		Long ailleurs = emplacement("MAGASIN", null);
		Long modele = modele("Commercial", true);
		modele("SansPrix", false);
		chassis(modele, "DISPONIBLE", magasin, 3, 5, 800_000L);
		chassis(modele, "DISPONIBLE", magasin, 1, 120, 800_000L);
		chassis(modele, "RESERVEE", magasin, 2, 5, 800_000L);
		chassis(modele, "DISPONIBLE", ailleurs, 9, 5, 800_000L);
		String role = c.role(false, "clients.voir", "stock.voir", "catalogue.voir");
		ClientHttp commercial = c.connecter(c.utilisateurActif(role, magasin));

		commercial.get("/api/v1/tableau-de-bord/commercial").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.disponibilite.disponible").value(4)).andExpect(jsonPath("$.data.disponibilite.reserve").value(2))
				.andExpect(jsonPath("$.data.disponibilite.physique").value(6))
				.andExpect(jsonPath("$.data.modeles[?(@.modele_id==%d)].disponible".formatted(modele)).value(hasItem(4)))
				.andExpect(jsonPath("$.data.modeles[?(@.modele_id==%d)].prix_vente".formatted(modele)).value(hasItem(1500000)))
				.andExpect(jsonPath("$.data.anciennes.nombre").value(1)).andExpect(jsonPath("$.data.anciennes.seuil_jours").value(90))
				.andExpect(jsonPath("$.data.anciennes.plus_anciennes[0].jours").value(120))
				.andExpect(jsonPath("$.data.modeles_sans_prix").isNumber())
				.andExpect(jsonPath("$.data.alertes[*].message").value(hasItem(org.hamcrest.Matchers.containsString("prix de vente"))))
				.andExpect(jsonPath("$.data.rupture.nombre").isNumber());
		// Sans emplacement : rien à vendre, mais le tableau répond.
		c.connecter(c.utilisateurActif(role)).get("/api/v1/tableau-de-bord/commercial").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.disponibilite.disponible").value(0)).andExpect(jsonPath("$.data.anciennes.nombre").value(0));
		// Les droits : le magasin n'a pas de droit sur les clients, la personne non connectée n'a rien.
		c.connecter(c.utilisateurActif("magasin")).get("/api/v1/tableau-de-bord/commercial").andExpect(status().isForbidden());
		new ClientHttp(mvc).get("/api/v1/tableau-de-bord/commercial").andExpect(status().isUnauthorized());
	}

	@Test
	void leDomaineManagementConsolideStockApprovisionnementsEtControle() throws Exception {
		ComptesDeTest c = comptes();
		ClientHttp admin = c.admin();
		Long magasin = emplacement("MAGASIN", null);
		Long modele = modele("Management", true);
		chassis(modele, "DISPONIBLE", magasin, 4, 10, 800_000L);
		chassis(modele, "BLOQUEE", magasin, 1, 10, null);
		chassis(modele, "DISPONIBLE", magasin, 1, 10, 100_000L);

		// Un arrivage en retard : date prévue dépassée, marchandise pas encore arrivée.
		String nom = "Fournisseur " + unique("");
		admin.post("/api/v1/fournisseurs", "{\"raison_sociale\":\"%s\",\"pays\":\"Chine\",\"devise\":\"USD\",\"contacts\":[]}".formatted(nom)).andExpect(status().isCreated());
		Long fournisseur = jdbc.sql("SELECT id FROM fournisseurs WHERE raison_sociale = :n").param("n", nom).query(Long.class).single();
		String reponse = admin.post("/api/v1/commandes-fournisseur", ("{\"fournisseur_id\":%d,\"devise\":\"USD\",\"taux_change\":600,\"incoterm\":\"FOB\",\"base_prix\":\"FOB\","
				+ "\"lignes\":[{\"modele_id\":%d,\"quantite\":2,\"prix_unitaire\":1000}]}").formatted(fournisseur, modele)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		Long commande = Long.valueOf(JsonPath.read(reponse, "$.data.id").toString());
		admin.post("/api/v1/commandes-fournisseur/" + commande + "/avancer", "{\"reference_proforma\":\"PI-9\"}").andExpect(status().isOk());
		String numeroBl = unique("BL");
		admin.post("/api/v1/commandes-fournisseur/" + commande + "/arrivages", "{\"numero_bl\":\"%s\",\"date_arrivee_prevue\":\"%s\"}".formatted(numeroBl, LocalDate.now().minusDays(20)))
				.andExpect(status().isCreated());

		ClientHttp direction = c.connecter(c.utilisateurActif(c.roleGlobal("approvisionnements.voir", "stock.voir")));
		direction.get("/api/v1/tableau-de-bord/management").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.stock.consolide").value(true)).andExpect(jsonPath("$.data.stock.indicateurs.physique").isNumber())
				.andExpect(jsonPath("$.data.stock.emplacements[?(@.id==%d)].indicateurs.physique".formatted(magasin)).value(hasItem(6)))
				.andExpect(jsonPath("$.data.stock.valorisation").value(nullValue()))
				.andExpect(jsonPath("$.data.approvisionnements.commandes_en_cours").isNumber())
				.andExpect(jsonPath("$.data.approvisionnements.par_statut[?(@.code=='CONFIRMEE')].libelle").value(hasItem("Confirmée")))
				.andExpect(jsonPath("$.data.approvisionnements.arrivages_en_retard").isNumber())
				.andExpect(jsonPath("$.data.approvisionnements.retards[?(@.numero_bl=='%s')].retard_jours".formatted(numeroBl)).value(hasItem(20)))
				.andExpect(jsonPath("$.data.alertes[*].message").value(hasItem(org.hamcrest.Matchers.containsString("en retard"))))
				.andExpect(jsonPath("$.data.controle.chassis_bloques").isNumber()).andExpect(jsonPath("$.data.controle.transferts_en_transit").isNumber()).andExpect(jsonPath("$.data.controle.transferts_en_retard").isNumber()).andExpect(jsonPath("$.data.receptions.brouillons").isNumber());

		// Avec le droit de voir les prix d'achat, la valorisation apparaît, et les motos sans coût sont signalées à part.
		ClientHttp finance = c.connecter(c.utilisateurActif(c.roleGlobal("approvisionnements.voir", "stock.voir", "prix_achat.voir")));
		finance.get("/api/v1/tableau-de-bord/management").andExpect(status().isOk()).andExpect(jsonPath("$.data.stock.valorisation.montant_fcfa").isNumber())
				.andExpect(jsonPath("$.data.stock.valorisation.chassis_sans_cout").isNumber());

		// Vue limitée au périmètre : le stock d'un autre magasin n'y figure pas.
		ClientHttp responsable = c.connecter(c.utilisateurActif(c.role(false, "approvisionnements.voir", "stock.voir"), emplacement("MAGASIN", null)));
		responsable.get("/api/v1/tableau-de-bord/management").andExpect(status().isOk()).andExpect(jsonPath("$.data.stock.consolide").value(false))
				.andExpect(jsonPath("$.data.stock.emplacements.length()").value(1));
		// Les droits.
		c.connecter(c.utilisateurActif("boutique")).get("/api/v1/tableau-de-bord/management").andExpect(status().isForbidden());
		new ClientHttp(mvc).get("/api/v1/tableau-de-bord/management").andExpect(status().isUnauthorized());
		// Le domaine Administration reste réservé à l'administration.
		c.connecter(c.utilisateurActif("magasin")).get("/api/v1/tableau-de-bord/administration").andExpect(status().isForbidden());
	}

}
