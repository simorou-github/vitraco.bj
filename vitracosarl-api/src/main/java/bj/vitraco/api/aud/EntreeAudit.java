package bj.vitraco.api.aud;

import bj.vitraco.api.common.CodeModule;

/**
 * Ce que l'on veut inscrire au journal (EF-AUD-01). L'utilisateur, l'heure et
 * l'adresse IP sont ajoutés par le service ; les valeurs sont des objets
 * sérialisables en JSON (Map, record…), ou {@code null}.
 */
public record EntreeAudit(
		CodeModule module,
		String action,
		String objetType,
		String objetId,
		Object ancienneValeur,
		Object nouvelleValeur,
		String motif,
		/** Renseignés quand l'acteur n'est pas encore celui de la session, par exemple à la connexion. */
		Long acteurId,
		String acteurLibelle) {

	public static EntreeAudit de(CodeModule module, String action, String objetType, String objetId) {
		return new EntreeAudit(module, action, objetType, objetId, null, null, null, null, null);
	}

	public EntreeAudit avecValeurs(Object ancienne, Object nouvelle) {
		return new EntreeAudit(module, action, objetType, objetId, ancienne, nouvelle, motif, acteurId, acteurLibelle);
	}

	public EntreeAudit avecMotif(String motif) {
		return new EntreeAudit(module, action, objetType, objetId, ancienneValeur, nouvelleValeur, motif, acteurId, acteurLibelle);
	}

	public EntreeAudit avecActeur(Long id, String libelle) {
		return new EntreeAudit(module, action, objetType, objetId, ancienneValeur, nouvelleValeur, motif, id, libelle);
	}

}
