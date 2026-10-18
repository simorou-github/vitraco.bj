package bj.vitraco.api.app;

import java.util.Optional;

/**
 * Workflow d'une commande fournisseur (SRS 3.5) : huit statuts qui se suivent, plus ANNULEE. Les trois derniers
 * (à réceptionner, réception partielle, réceptionnée) découlent de l'arrivée de la marchandise : les deux derniers se
 * posent seuls à la validation des réceptions et ne s'avancent jamais à la main.
 */
public enum StatutCommande {
	COMMANDEE("Commandée"),
	CONFIRMEE("Confirmée"),
	EN_TRANSIT("En transit"),
	AU_PORT("Au port"),
	DEDOUANEMENT("Dédouanement"),
	A_RECEPTIONNER("À réceptionner"),
	RECEPTION_PARTIELLE("Réception partielle"),
	RECEPTIONNEE("Réceptionnée"),
	ANNULEE("Annulée");

	private final String libelle;

	StatutCommande(String libelle) {
		this.libelle = libelle;
	}

	public String libelle() {
		return libelle;
	}

	/** Étape qui peut s'avancer à la main : de COMMANDEE jusqu'à A_RECEPTIONNER. */
	public Optional<StatutCommande> suivant() {
		return ordinal() < A_RECEPTIONNER.ordinal() ? Optional.of(values()[ordinal() + 1]) : Optional.empty();
	}

	/** Retour d'une étape, possible de CONFIRMEE jusqu'à A_RECEPTIONNER (avant toute réception). */
	public Optional<StatutCommande> precedent() {
		return ordinal() >= CONFIRMEE.ordinal() && ordinal() <= A_RECEPTIONNER.ordinal() ? Optional.of(values()[ordinal() - 1]) : Optional.empty();
	}

	/** L'annulation n'est possible qu'avant le départ de la marchandise (avant EN TRANSIT). */
	public boolean annulable() {
		return this == COMMANDEE || this == CONFIRMEE;
	}

	/** Les lignes et les prix ne se modifient que tant que le fournisseur n'a pas confirmé. */
	public boolean modifiable() {
		return this == COMMANDEE;
	}

	public boolean recoitDesReceptions() {
		return this == A_RECEPTIONNER || this == RECEPTION_PARTIELLE;
	}

}
