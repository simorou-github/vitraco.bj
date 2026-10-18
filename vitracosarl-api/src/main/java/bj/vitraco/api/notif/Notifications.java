package bj.vitraco.api.notif;

/**
 * Porte d'entrée des modules métier vers les notifications (EF-NOT-01). L'appel se fait dans la
 * transaction de l'opération concernée : la notification existe si et seulement si l'opération est
 * validée. Le courriel qui la double part après la validation, hors du fil de la requête, et ne
 * fait jamais échouer l'opération.
 */
public interface Notifications {

	/** Notifie tous les utilisateurs actifs qui ont ce rôle. */
	void notifierRole(String codeRole, DemandeNotification demande);

	void notifierUtilisateur(Long utilisateurId, DemandeNotification demande);

}
