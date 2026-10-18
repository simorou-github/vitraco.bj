package bj.vitraco.api.tdb;

import bj.vitraco.api.tdb.SyntheseAdministration.Alerte;

import java.util.List;

/**
 * Le point de vue du commercial : de quoi vendre aujourd'hui. Les indicateurs de vente (commandes, factures, créances)
 * viendront avec leurs modules ; en attendant, ce qui se vend se lit sur le stock et le catalogue de prix.
 */
public record SyntheseCommerciale(
		Disponibilite disponibilite,
		List<ModeleAVendre> modeles,
		ModelesSansStock rupture,
		long modelesSansPrix,
		MotosAnciennes anciennes,
		List<Alerte> alertes) {

	/** Dans les emplacements de la personne connectée. */
	public record Disponibilite(int disponible, int reserve, int bloque, int physique) {
	}

	public record ModeleAVendre(Long modeleId, String modele, String codeArticle, int disponible, int reserve, Long prixVente) {
	}

	public record ModelesSansStock(long nombre, List<String> exemples) {
	}

	/** Motos disponibles depuis longtemps : à écouler en priorité. */
	public record MotosAnciennes(long nombre, int seuilJours, List<MotoAncienne> plusAnciennes) {
	}

	public record MotoAncienne(Long id, String numero, String modele, String emplacement, long jours) {
	}

}
