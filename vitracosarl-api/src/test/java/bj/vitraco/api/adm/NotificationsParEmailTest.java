package bj.vitraco.api.adm;

import bj.vitraco.api.TestcontainersConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** La notification qui double un événement part bien par e-mail, vers le bon destinataire, avec le lien de l'écran. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
		"spring.mail.host=localhost", "spring.mail.port=3025",
		"spring.mail.properties.mail.smtp.ssl.enable=false", "spring.mail.properties.mail.smtp.auth=false",
		"vitraco.mail.from=infos@exemple.test", "vitraco.notifications.app-url=http://app.test/#"})
class NotificationsParEmailTest {

	@RegisterExtension
	static GreenMailExtension smtp = new GreenMailExtension(ServerSetupTest.SMTP);

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;

	private static String contenu(MimeMessage message) throws Exception {
		StringBuilder texte = new StringBuilder();
		aplatir(message.getContent(), texte);
		return texte.toString();
	}

	private static void aplatir(Object contenu, StringBuilder sortie) throws Exception {
		if (contenu instanceof MimeMultipart multi) {
			for (int i = 0; i < multi.getCount(); i++) {
				aplatir(multi.getBodyPart(i).getContent(), sortie);
			}
		} else {
			sortie.append(contenu).append('\n');
		}
	}

	@Test
	void laDirectionRecoitParEmailLeChangementDeDroitsAvecLeLienDeLEcran() throws Exception {
		ComptesDeTest comptes = new ComptesDeTest(mvc, jdbc);
		String direction = comptes.utilisateurActif("direction");
		String cible = comptes.utilisateurActif("commercial");

		comptes.admin().put("/api/v1/utilisateurs/" + comptes.id(cible),
				"{\"nom\":\"Test\",\"prenom\":\"Compte\",\"email\":\"%s\",\"roles\":[\"magasin\"]}".formatted(cible)).andExpect(status().isOk());

		assertThat(smtp.waitForIncomingEmail(8000, 1)).isTrue();
		MimeMessage recu = null;
		for (int i = 0; i < 20 && recu == null; i++) {
			recu = Arrays.stream(smtp.getReceivedMessages()).filter(m -> {
				try {
					return "Droits modifiés : Compte Test".equals(m.getSubject()) && m.getAllRecipients()[0].toString().equals(direction);
				} catch (Exception e) {
					return false;
				}
			}).findFirst().orElse(null);
			if (recu == null) {
				Thread.sleep(250);
			}
		}
		assertThat(recu).as("e-mail de notification reçu par la Direction").isNotNull();
		assertThat(recu.getFrom()[0].toString()).contains("infos@exemple.test");
		assertThat(contenu(recu)).contains("Rôles : commercial → magasin")
				.contains("http://app.test/#/utilisateurs/" + comptes.id(cible)).contains("Ouvrir dans VITRACO Moto");
	}

}
