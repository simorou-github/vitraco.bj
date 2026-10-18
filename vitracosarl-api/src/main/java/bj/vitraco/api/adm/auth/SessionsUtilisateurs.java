package bj.vitraco.api.adm.auth;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Ferme les sessions d'un utilisateur : suspension, changement de rôle ou de mot de passe
 * coupent l'accès immédiatement (SRS 5.1). Exécuté après validation de la transaction.
 */
@Component
public class SessionsUtilisateurs {

	private final FindByIndexNameSessionRepository<? extends Session> sessions;

	SessionsUtilisateurs(FindByIndexNameSessionRepository<? extends Session> sessions) {
		this.sessions = sessions;
	}

	public void fermerApresValidation(Collection<String> emails) {
		List<String> aFermer = List.copyOf(emails);
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					aFermer.forEach(SessionsUtilisateurs.this::fermer);
				}
			});
		} else {
			aFermer.forEach(this::fermer);
		}
	}

	public void fermerApresValidation(String email) {
		fermerApresValidation(List.of(email));
	}

	private void fermer(String email) {
		Map<String, ? extends Session> ouvertes = sessions.findByPrincipalName(email);
		List.copyOf(ouvertes.keySet()).forEach(sessions::deleteById);
	}

}
