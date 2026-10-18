package bj.vitraco.api.notif;

/**
 * Ce qu'un module demande de notifier. Le {@code type} est un code stable (par exemple
 * « droits.modifies ») sur lequel s'appuieront plus tard les abonnements par rôle (EF-NOT-05) ;
 * le {@code lien} est la route de l'écran de traitement (EF-NOT-03).
 */
public record DemandeNotification(String type, String titre, String message, String lien, boolean parEmail) {

	public static DemandeNotification de(String type, String titre, String message, String lien) {
		return new DemandeNotification(type, titre, message, lien, true);
	}

	public DemandeNotification sansEmail() {
		return new DemandeNotification(type, titre, message, lien, false);
	}

}
