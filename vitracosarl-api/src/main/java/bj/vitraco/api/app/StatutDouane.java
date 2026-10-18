package bj.vitraco.api.app;

import java.util.Optional;

/** Avancement du dédouanement d'un arrivage : il ne recule pas, et il commande les étapes « dédouanement » et « à réceptionner ». */
public enum StatutDouane {
	NON_DEMARREE("Non démarré"),
	EN_COURS("En cours"),
	TERMINEE("Terminé");

	private final String libelle;

	StatutDouane(String libelle) {
		this.libelle = libelle;
	}

	public String libelle() {
		return libelle;
	}

	public Optional<StatutDouane> suivant() {
		return this == TERMINEE ? Optional.empty() : Optional.of(values()[ordinal() + 1]);
	}

}
