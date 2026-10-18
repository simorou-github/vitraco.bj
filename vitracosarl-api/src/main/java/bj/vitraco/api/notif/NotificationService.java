package bj.vitraco.api.notif;

import bj.vitraco.api.adm.auth.MessagerieProprietes;
import jakarta.annotation.PreDestroy;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
class NotificationService implements Notifications {

	private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

	private record Destinataire(Long id, String email, String nom) {
	}

	private final JdbcClient jdbc;
	private final ObjectProvider<JavaMailSender> expediteur;
	private final MessagerieProprietes messagerie;
	private final NotifProprietes proprietes;
	private final ExecutorService executeur = Executors.newVirtualThreadPerTaskExecutor();

	NotificationService(JdbcClient jdbc, ObjectProvider<JavaMailSender> expediteur, MessagerieProprietes messagerie, NotifProprietes proprietes) {
		this.jdbc = jdbc;
		this.expediteur = expediteur;
		this.messagerie = messagerie;
		this.proprietes = proprietes;
	}

	@PreDestroy
	void arreter() {
		executeur.close();
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void notifierRole(String codeRole, DemandeNotification demande) {
		List<Destinataire> destinataires = jdbc.sql("""
						SELECT DISTINCT u.id, u.email, trim(coalesce(u.prenom || ' ', '') || u.nom) AS nom
						FROM utilisateurs u
						JOIN utilisateur_role ur ON ur.utilisateur_id = u.id
						JOIN roles r ON r.id = ur.role_id
						WHERE r.code = :role AND u.statut = 'ACTIF'""")
				.param("role", codeRole)
				.query((rs, n) -> new Destinataire(rs.getLong("id"), rs.getString("email"), rs.getString("nom")))
				.list();
		diffuser(destinataires, demande);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void notifierUtilisateur(Long utilisateurId, DemandeNotification demande) {
		List<Destinataire> destinataires = jdbc.sql("""
						SELECT id, email, trim(coalesce(prenom || ' ', '') || nom) AS nom
						FROM utilisateurs WHERE id = :id AND statut <> 'SUSPENDU'""")
				.param("id", utilisateurId)
				.query((rs, n) -> new Destinataire(rs.getLong("id"), rs.getString("email"), rs.getString("nom")))
				.list();
		diffuser(destinataires, demande);
	}

	private void diffuser(List<Destinataire> destinataires, DemandeNotification d) {
		for (Destinataire destinataire : destinataires) {
			jdbc.sql("""
							INSERT INTO notifications (destinataire_id, type, titre, message, lien)
							VALUES (:destinataire, :type, :titre, :message, :lien)""")
					.param("destinataire", destinataire.id())
					.param("type", d.type())
					.param("titre", d.titre())
					.param("message", d.message())
					.param("lien", d.lien())
					.update();
			if (d.parEmail()) {
				apresValidation(() -> envoyer(destinataire, d));
			}
		}
	}

	private void apresValidation(Runnable envoi) {
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

	private void envoyer(Destinataire destinataire, DemandeNotification d) {
		JavaMailSender sender = expediteur.getIfAvailable();
		if (sender == null || messagerie.from().isBlank()) {
			log.info("[DEV] Messagerie non configurée. Notification « {} » pour {}", d.titre(), destinataire.email());
			return;
		}
		try {
			String lien = d.lien() == null ? null : proprietes.appUrl() + d.lien();
			MimeMessage message = sender.createMimeMessage();
			MimeMessageHelper aide = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
			aide.setFrom(messagerie.from(), messagerie.fromName());
			aide.setTo(destinataire.email());
			aide.setSubject(d.titre());
			aide.setText(texteBrut(destinataire, d, lien), html(destinataire, d, lien));
			sender.send(message);
			log.info("Notification « {} » envoyée par e-mail à {}", d.titre(), destinataire.email());
		} catch (Exception e) {
			log.error("Échec de l'envoi de la notification « {} » à {} : {}", d.titre(), destinataire.email(), e.getMessage());
		}
	}

	private static String salutation(Destinataire d) {
		return d.nom() == null || d.nom().isBlank() ? "Bonjour," : "Bonjour " + d.nom() + ",";
	}

	private static String texteBrut(Destinataire destinataire, DemandeNotification d, String lien) {
		return salutation(destinataire) + "\n\n" + d.titre() + "\n\n" + d.message()
				+ (lien == null ? "" : "\n\nOuvrir : " + lien) + "\n\nVITRACO Moto\n";
	}

	private static String html(Destinataire destinataire, DemandeNotification d, String lien) {
		String bouton = lien == null ? "" : """
				    <p style="margin:0 0 8px">
				      <a href="%s" style="background:#0b6fb8;color:#ffffff;text-decoration:none;padding:12px 24px;border-radius:8px;display:inline-block">Ouvrir dans VITRACO Moto</a>
				    </p>
				""".formatted(HtmlUtils.htmlEscape(lien));
		return """
				<div style="font-family:Arial,Helvetica,sans-serif;background:#f3f4f6;padding:24px">
				  <div style="max-width:520px;margin:0 auto;background:#ffffff;border-radius:12px;padding:32px;color:#1f2937">
				    <h2 style="margin:0 0 16px;font-size:20px">%s</h2>
				    <p style="margin:0 0 12px">%s</p>
				    <p style="margin:0 0 24px;line-height:1.5">%s</p>
				%s  </div>
				</div>
				""".formatted(HtmlUtils.htmlEscape(d.titre()), HtmlUtils.htmlEscape(salutation(destinataire)),
				HtmlUtils.htmlEscape(d.message()).replace("\n", "<br>"), bouton);
	}

}
