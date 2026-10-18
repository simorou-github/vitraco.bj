package bj.vitraco.api.adm.auth;

/**
 * Issue d'une tentative de connexion ; l'identité n'est renseignée qu'en cas de succès. Les deux
 * issues « A2F » signifient que le mot de passe est bon mais que la connexion n'est pas terminée :
 * soit un code doit être saisi, soit le second facteur doit d'abord être configuré.
 */
public record ResultatConnexion(Issue issue, UtilisateurConnecte identite, Long utilisateurId) {

	public enum Issue {
		SUCCES, IDENTIFIANTS_INVALIDES, COMPTE_VERROUILLE, COMPTE_SUSPENDU, A2F_VERIFICATION_REQUISE, A2F_CONFIGURATION_REQUISE, CODE_A2F_INVALIDE
	}

	static ResultatConnexion echec(Issue issue) {
		return new ResultatConnexion(issue, null, null);
	}

	static ResultatConnexion enAttente(Issue issue, Long utilisateurId) {
		return new ResultatConnexion(issue, null, utilisateurId);
	}

}
