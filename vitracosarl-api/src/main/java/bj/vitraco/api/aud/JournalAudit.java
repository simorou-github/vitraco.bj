package bj.vitraco.api.aud;

import java.util.OptionalLong;

/**
 * Journal d'audit métier, en ajout seul (RG-18, EF-AUD-05). Chaque module l'appelle
 * dans la transaction de l'opération qu'il trace : l'opération et sa trace sont
 * validées ou annulées ensemble (ENF-13).
 */
public interface JournalAudit {

	void enregistrer(EntreeAudit entree);

	/** Rang de la première entrée altérée, ou vide si la chaîne d'empreintes est intacte (EF-AUD-10). */
	OptionalLong premiereAlteration();

}
