package bj.vitraco.api.adm;

import bj.vitraco.api.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class NotificationsTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;

	private ComptesDeTest comptes() {
		return new ComptesDeTest(mvc, jdbc);
	}

	private long nonLues(Long utilisateurId, String type) {
		return jdbc.sql("SELECT count(*) FROM notifications WHERE destinataire_id = :u AND type = :t AND lue_le IS NULL")
				.param("u", utilisateurId).param("t", type).query(Long.class).single();
	}

	@Test
	void unChangementDeRolesNotifieLaDirectionAvecAncienNouveauEtAuteur() throws Exception {
		ComptesDeTest comptes = comptes();
		String direction = comptes.utilisateurActif("direction");
		String cible = comptes.utilisateurActif("commercial");
		Long idDirection = comptes.id(direction);

		comptes.admin().put("/api/v1/utilisateurs/" + comptes.id(cible),
				"{\"nom\":\"Test\",\"prenom\":\"Compte\",\"email\":\"%s\",\"roles\":[\"magasin\"]}".formatted(cible)).andExpect(status().isOk());

		ClientHttp client = comptes.connecter(direction);
		client.get("/api/v1/notifications?non_lues=true").andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].type").value("droits.modifies"))
				.andExpect(jsonPath("$.data[0].titre").value("Droits modifiés : Compte Test"))
				.andExpect(jsonPath("$.data[0].message").value(containsString("Rôles : commercial → magasin")))
				.andExpect(jsonPath("$.data[0].message").value(containsString("Administrateur")))
				.andExpect(jsonPath("$.data[0].lien").value("/utilisateurs/" + comptes.id(cible)))
				.andExpect(jsonPath("$.data[0].lue").value(false))
				.andExpect(jsonPath("$.meta.total").exists());
		assertThat(nonLues(idDirection, "droits.modifies")).isGreaterThanOrEqualTo(1);
	}

	@Test
	void laCreationDUnCompteNotifieAussiLaDirection() throws Exception {
		ComptesDeTest comptes = comptes();
		String direction = comptes.utilisateurActif("direction");
		Long avant = nonLues(comptes.id(direction), "droits.modifies");
		String cible = comptes.utilisateurActif("boutique");

		assertThat(nonLues(comptes.id(direction), "droits.modifies")).isEqualTo(avant + 1);
		comptes.connecter(direction).get("/api/v1/notifications?per_page=1").andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].titre").value("Nouvel utilisateur : Compte Test"))
				.andExpect(jsonPath("$.data[0].message").value(containsString(cible)));
	}

	@Test
	void unChangementDeDroitsDUnRoleDonneLesPermissionsAjouteesEtRetirees() throws Exception {
		ComptesDeTest comptes = comptes();
		String direction = comptes.utilisateurActif("direction");
		String code = comptes.role(false, "stock.voir", "rapports.voir");
		Long idRole = jdbc.sql("SELECT id FROM roles WHERE code = :c").param("c", code).query(Long.class).single();

		comptes.admin().put("/api/v1/roles/" + idRole, "{\"libelle\":\"Rôle test\",\"permissions\":[\"stock.voir\",\"clients.voir\"]}").andExpect(status().isOk());

		comptes.connecter(direction).get("/api/v1/notifications?per_page=1").andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].titre").value("Droits du rôle modifiés : Rôle test"))
				.andExpect(jsonPath("$.data[0].message").value(containsString("Ajoutés : clients.voir")))
				.andExpect(jsonPath("$.data[0].message").value(containsString("Retirés : rapports.voir")))
				.andExpect(jsonPath("$.data[0].lien").value("/utilisateurs/roles/" + idRole));
	}

	@Test
	void unSimpleRenommageDeRoleNeNotifiePersonne() throws Exception {
		ComptesDeTest comptes = comptes();
		String direction = comptes.utilisateurActif("direction");
		String code = comptes.role(false, "stock.voir");
		Long idRole = jdbc.sql("SELECT id FROM roles WHERE code = :c").param("c", code).query(Long.class).single();
		long avant = jdbc.sql("SELECT count(*) FROM notifications WHERE destinataire_id = :u").param("u", comptes.id(direction)).query(Long.class).single();

		comptes.admin().put("/api/v1/roles/" + idRole, "{\"libelle\":\"Autre nom\",\"permissions\":[\"stock.voir\"]}").andExpect(status().isOk());

		assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE destinataire_id = :u").param("u", comptes.id(direction)).query(Long.class).single()).isEqualTo(avant);
	}

	@Test
	void chacunNeVoitEtNeMarqueQueSesPropresNotifications() throws Exception {
		ComptesDeTest comptes = comptes();
		String direction = comptes.utilisateurActif("direction");
		String autre = comptes.utilisateurActif("direction");
		comptes.utilisateurActif("magasin");

		ClientHttp client = comptes.connecter(direction);
		Long id = jdbc.sql("SELECT id FROM notifications WHERE destinataire_id = :u ORDER BY id DESC LIMIT 1")
				.param("u", comptes.id(direction)).query(Long.class).single();
		long avant = nonLues(comptes.id(direction), "droits.modifies");

		// Un autre utilisateur ne peut pas marquer la notification d'autrui comme lue.
		comptes.connecter(autre).post("/api/v1/notifications/" + id + "/lire", "{}").andExpect(status().isNoContent());
		assertThat(jdbc.sql("SELECT lue_le FROM notifications WHERE id = :i").param("i", id).query(java.sql.Timestamp.class).optional()).isEmpty();

		client.post("/api/v1/notifications/" + id + "/lire", "{}").andExpect(status().isNoContent());
		assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE id = :i AND lue_le IS NOT NULL").param("i", id).query(Long.class).single()).isEqualTo(1);
		client.get("/api/v1/notifications/non-lues/nombre").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.nombre").value(nonLuesTous(comptes.id(direction))));
		assertThat(nonLues(comptes.id(direction), "droits.modifies")).isEqualTo(avant - 1);

		client.post("/api/v1/notifications/lire-tout", "{}").andExpect(status().isNoContent());
		client.get("/api/v1/notifications/non-lues/nombre").andExpect(jsonPath("$.data.nombre").value(0));
		client.get("/api/v1/notifications?non_lues=true").andExpect(jsonPath("$.data.length()").value(0));
		client.get("/api/v1/notifications").andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
	}

	private long nonLuesTous(Long utilisateurId) {
		return jdbc.sql("SELECT count(*) FROM notifications WHERE destinataire_id = :u AND lue_le IS NULL").param("u", utilisateurId).query(Long.class).single();
	}

	@Test
	void lesNotificationsDemandentUneConnexion() throws Exception {
		new ClientHttp(mvc).get("/api/v1/notifications").andExpect(status().isUnauthorized());
		new ClientHttp(mvc).get("/api/v1/notifications/non-lues/nombre").andExpect(status().isUnauthorized());
	}

	@Test
	void unCompteVerrouilleEstSignaleAuxAdministrateurs() throws Exception {
		ComptesDeTest comptes = comptes();
		String email = comptes.utilisateurActif("magasin");
		long avant = nonLues(comptes.id("admin@vitraco.bj"), "compte.verrouille");

		for (int i = 0; i < 5; i++) {
			new ClientHttp(mvc).post("/api/v1/auth/login", "{\"email\":\"%s\",\"mot_de_passe\":\"faux-%s\"}".formatted(email, UUID.randomUUID()))
					.andExpect(status().is4xxClientError());
		}

		assertThat(nonLues(comptes.id("admin@vitraco.bj"), "compte.verrouille")).isEqualTo(avant + 1);
		comptes.admin().get("/api/v1/notifications?non_lues=true&per_page=1").andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].message").value(containsString(email)));
	}

	@Test
	void lesComptesSuspendusNeRecoiventPasDeNotification() throws Exception {
		ComptesDeTest comptes = comptes();
		String direction = comptes.utilisateurActif("direction");
		long avant = jdbc.sql("SELECT count(*) FROM notifications WHERE destinataire_id = :u").param("u", comptes.id(direction)).query(Long.class).single();
		comptes.admin().post("/api/v1/utilisateurs/" + comptes.id(direction) + "/suspendre", "{}").andExpect(status().isOk());

		comptes.utilisateurActif("boutique");

		assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE destinataire_id = :u").param("u", comptes.id(direction)).query(Long.class).single()).isEqualTo(avant);
	}

}
