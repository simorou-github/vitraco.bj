package bj.vitraco.api.tdb;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Ce que voit un administrateur en ouvrant l'application : l'état du paramétrage et ce qui reste à faire. */
public record SyntheseAdministration(
		Utilisateurs utilisateurs,
		Emplacements emplacements,
		Referentiels referentiels,
		Parametres parametres,
		long roles,
		List<EtapeMiseEnService> miseEnService,
		List<Alerte> alertes,
		/** Absente sans le droit de consulter le journal d'audit. */
		List<Activite> activite) {

	public record Utilisateurs(long total, long actifs, long enAttente, long suspendus, long verrouilles) {
	}

	public record Emplacements(long magasins, long boutiques, long desactives) {
	}

	public record Referentiels(long fournisseurs, long banques, long modesPaiement, long categoriesDepense, long centresCout) {
	}

	public record Parametres(Avancement fiscaux, Avancement controle) {
	}

	public record Avancement(long renseignes, long total) {
	}

	/** Une étape de la mise en service ; {@code lien} est la route de l'écran qui la traite. */
	public record EtapeMiseEnService(String code, String libelle, boolean faite, String lien) {
	}

	public record Alerte(Niveau niveau, String message, String lien) {

		public enum Niveau {INFO, ATTENTION, CRITIQUE}

	}

	public record Activite(Instant dateHeure, String utilisateur, String action, String module, String objetType, String objetId) {
	}

}
