package bj.vitraco.api.adm.auth;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Vérifie le contenu réel des e-mails sur un serveur SMTP de test (GreenMail), sans réseau externe. */
class EnvoiLienCompteMessagerieTest {

	@RegisterExtension
	static GreenMailExtension smtp = new GreenMailExtension(ServerSetupTest.SMTP);

	private static final DestinataireCompte MARIE = new DestinataireCompte("marie@test.vitraco.bj", "Marie Dossou");
	private static final String LIEN = "http://localhost:4200/#/authentication/activation?token=abc_DEF-123";

	@SuppressWarnings("unchecked")
	private EnvoiLienCompteMessagerie avec(JavaMailSender sender, String from) {
		ObjectProvider<JavaMailSender> fournisseur = mock(ObjectProvider.class);
		when(fournisseur.getIfAvailable()).thenReturn(sender);
		return new EnvoiLienCompteMessagerie(fournisseur, new MessagerieProprietes(from, "VITRACO Moto"));
	}

	private JavaMailSenderImpl serveurDeTest() {
		JavaMailSenderImpl sender = new JavaMailSenderImpl();
		sender.setHost("localhost");
		sender.setPort(ServerSetupTest.SMTP.getPort());
		return sender;
	}

	/** Texte brut et HTML de toutes les parties du message, y compris imbriquées. */
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
	void lEmailDActivationArriveAvecLeLienLaValiditeEtLExpediteurConfigure() throws Exception {
		avec(serveurDeTest(), "infos@exemple.test").envoyerActivation(MARIE, LIEN, Duration.ofHours(24));

		assertThat(smtp.waitForIncomingEmail(5000, 1)).isTrue();
		MimeMessage recu = smtp.getReceivedMessages()[0];
		assertThat(recu.getAllRecipients()[0].toString()).isEqualTo("marie@test.vitraco.bj");
		assertThat(recu.getFrom()[0].toString()).contains("infos@exemple.test").contains("VITRACO Moto");
		assertThat(recu.getSubject()).isEqualTo("Activez votre compte VITRACO Moto");
		assertThat(contenu(recu)).contains("Bonjour Marie Dossou,").contains(LIEN).contains("24 heures").contains("Activer mon compte");
	}

	@Test
	void lEmailDeReinitialisationIndiqueSaDureeEnMinutes() throws Exception {
		avec(serveurDeTest(), "infos@exemple.test").envoyerReinitialisation(MARIE, LIEN, Duration.ofMinutes(45));

		assertThat(smtp.waitForIncomingEmail(5000, 1)).isTrue();
		assertThat(contenu(smtp.getReceivedMessages()[0])).contains("45 minutes").contains("ignorez ce message");
	}

	@Test
	void sansServeurSmtpConfigureRienNEstEnvoyeEtAucuneErreurNeRemonte() {
		assertThatCode(() -> avec(null, "").envoyerActivation(MARIE, LIEN, Duration.ofHours(24))).doesNotThrowAnyException();
		assertThatCode(() -> avec(serveurDeTest(), "").envoyerActivation(MARIE, LIEN, Duration.ofHours(24))).doesNotThrowAnyException();
		assertThat(smtp.getReceivedMessages()).isEmpty();
	}

	@Test
	void uneEnPanneDuServeurSmtpNeFaitJamaisEchouerLOperationMetier() {
		JavaMailSenderImpl inaccessible = new JavaMailSenderImpl();
		inaccessible.setHost("localhost");
		inaccessible.setPort(1); // personne n'écoute
		assertThatCode(() -> avec(inaccessible, "infos@exemple.test").envoyerActivation(MARIE, LIEN, Duration.ofHours(24)))
				.doesNotThrowAnyException();
	}

	@Test
	void lesDureesSAffichentEnLangageNaturel() {
		assertThat(EnvoiLienCompteMessagerie.duree(Duration.ofHours(24))).isEqualTo("24 heures");
		assertThat(EnvoiLienCompteMessagerie.duree(Duration.ofHours(1))).isEqualTo("1 heure");
		assertThat(EnvoiLienCompteMessagerie.duree(Duration.ofMinutes(60 * 24 + 30))).isEqualTo("1470 minutes");
	}

}
