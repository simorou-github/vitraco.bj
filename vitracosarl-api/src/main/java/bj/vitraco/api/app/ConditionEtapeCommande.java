package bj.vitraco.api.app;

import java.util.Optional;

/**
 * Point d'extension : un module qui pose une condition à un changement d'étape le dit ici. Les arrivages y déclareront
 * qu'il faut un arrivage pour passer EN TRANSIT, une date d'arrivée réelle pour passer AU PORT, etc. Sans implémentation,
 * rien ne s'oppose au changement.
 */
public interface ConditionEtapeCommande {

	/** Motif du refus pour passer la commande à {@code cible}, ou vide si ce module ne s'y oppose pas. */
	Optional<String> refus(Long commandeId, StatutCommande cible);

}
