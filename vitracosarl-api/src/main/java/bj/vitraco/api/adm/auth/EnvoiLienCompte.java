package bj.vitraco.api.adm.auth;

import java.time.Duration;

/**
 * Remise d'un lien d'activation ou de réinitialisation à son destinataire. L'implémentation
 * ne doit jamais faire échouer l'opération métier qui l'appelle : en cas de panne d'envoi,
 * l'administrateur peut renvoyer le lien.
 */
public interface EnvoiLienCompte {

	void envoyerActivation(DestinataireCompte destinataire, String lien, Duration validite);

	void envoyerReinitialisation(DestinataireCompte destinataire, String lien, Duration validite);

}
