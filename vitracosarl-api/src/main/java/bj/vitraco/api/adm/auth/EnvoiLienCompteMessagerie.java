package bj.vitraco.api.adm.auth;

import jakarta.annotation.PreDestroy;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Envoie les liens de compte par e-mail (SMTP). Sans serveur SMTP configuré, le lien est
 * simplement écrit dans le journal technique : pratique en développement, à ne jamais
 * laisser ainsi en production.
 *
 * <p>L'envoi se fait après la validation de la transaction (le lien n'existe alors vraiment
 * qu'une fois enregistré) et hors du fil de la requête : un serveur SMTP lent ou en panne
 * ne bloque ni ne fait échouer la création du compte.
 */
@Component
class EnvoiLienCompteMessagerie implements EnvoiLienCompte {

	private static final Logger log = LoggerFactory.getLogger(EnvoiLienCompteMessagerie.class);

	private final ObjectProvider<JavaMailSender> expediteur;
	private final MessagerieProprietes proprietes;
	private final ExecutorService executeur = Executors.newVirtualThreadPerTaskExecutor();

	EnvoiLienCompteMessagerie(ObjectProvider<JavaMailSender> expediteur, MessagerieProprietes proprietes) {
		this.expediteur = expediteur;
		this.proprietes = proprietes;
	}

	@PreDestroy
	void arreter() {
		executeur.close();
	}

	@Override
	public void envoyerActivation(DestinataireCompte d, String lien, Duration validite) {
		planifier(d, "Activez votre compte VITRACO Moto", "Bienvenue sur VITRACO Moto",
				"Un compte vient d'être créé pour vous. Cliquez sur le bouton ci-dessous pour choisir votre mot de passe et activer votre compte.",
				"Activer mon compte", lien, validite);
	}

	@Override
	public void envoyerReinitialisation(DestinataireCompte d, String lien, Duration validite) {
		planifier(d, "Réinitialisez votre mot de passe VITRACO Moto", "Réinitialisation du mot de passe",
				"Vous avez demandé à réinitialiser votre mot de passe. Cliquez sur le bouton ci-dessous pour en choisir un nouveau. Si vous n'êtes pas à l'origine de cette demande, ignorez ce message : votre mot de passe actuel reste valable.",
				"Choisir un nouveau mot de passe", lien, validite);
	}

	private void planifier(DestinataireCompte d, String sujet, String titre, String texte, String bouton, String lien, Duration validite) {
		Runnable envoi = () -> envoyer(d, sujet, titre, texte, bouton, lien, validite);
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					executeur.execute(envoi);
				}
			});
		} else {
			executeur.execute(envoi);
		}
	}

	private void envoyer(DestinataireCompte d, String sujet, String titre, String texte, String bouton, String lien, Duration validite) {
		JavaMailSender sender = expediteur.getIfAvailable();
		if (sender == null || proprietes.from().isBlank()) {
			log.info("[DEV] Messagerie non configurée. {} pour {} : {}", sujet, d.email(), lien);
			return;
		}
		try {
			MimeMessage message = sender.createMimeMessage();
			MimeMessageHelper aide = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
			aide.setFrom(proprietes.from(), proprietes.fromName());
			aide.setTo(d.email());
			aide.setSubject(sujet);
			aide.setText(texteBrut(d, titre, texte, lien, validite), html(d, titre, texte, bouton, lien, validite));
			sender.send(message);
			log.info("E-mail « {} » envoyé à {}", sujet, d.email());
		} catch (Exception e) {
			// Jamais le lien ni les identifiants SMTP dans cette trace.
			log.error("Échec de l'envoi de l'e-mail « {} » à {} : {}", sujet, d.email(), e.getMessage());
		}
	}

	static String duree(Duration validite) {
		return validite.toHours() >= 1 && validite.toMinutes() % 60 == 0
				? validite.toHours() + " heure" + (validite.toHours() > 1 ? "s" : "")
				: validite.toMinutes() + " minutes";
	}

	private static String salutation(DestinataireCompte d) {
		return d.nomComplet() == null || d.nomComplet().isBlank() ? "Bonjour," : "Bonjour " + d.nomComplet() + ",";
	}

	private static String texteBrut(DestinataireCompte d, String titre, String texte, String lien, Duration validite) {
		return """
				%s

				%s

				%s

				%s

				Ce lien est valable %s et ne peut être utilisé qu'une seule fois.

				VITRACO Moto
				""".formatted(salutation(d), titre, texte, lien, duree(validite));
	}

	private static String html(DestinataireCompte d, String titre, String texte, String bouton, String lien, Duration validite) {
		return """
				<div style="font-family:Arial,Helvetica,sans-serif;background:#f3f4f6;padding:24px">
				  <div style="max-width:520px;margin:0 auto;background:#ffffff;border-radius:12px;padding:32px;color:#1f2937">
				    <h2 style="margin:0 0 16px;font-size:20px">%s</h2>
				    <p style="margin:0 0 12px">%s</p>
				    <p style="margin:0 0 24px;line-height:1.5">%s</p>
				    <p style="margin:0 0 24px">
				      <a href="%s" style="background:#0b6fb8;color:#ffffff;text-decoration:none;padding:12px 24px;border-radius:8px;display:inline-block">%s</a>
				    </p>
				    <p style="margin:0 0 8px;font-size:13px;color:#6b7280">Ce lien est valable %s et ne peut être utilisé qu'une seule fois.</p>
				    <p style="margin:0;font-size:12px;color:#9ca3af;word-break:break-all">Si le bouton ne fonctionne pas, copiez ce lien dans votre navigateur : %s</p>
				  </div>
				</div>
				""".formatted(
				HtmlUtils.htmlEscape(titre), HtmlUtils.htmlEscape(salutation(d)), HtmlUtils.htmlEscape(texte),
				HtmlUtils.htmlEscape(lien), HtmlUtils.htmlEscape(bouton), duree(validite), HtmlUtils.htmlEscape(lien));
	}

}
