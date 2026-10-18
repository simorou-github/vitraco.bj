package bj.vitraco.api.cat;

import bj.vitraco.api.adm.utilisateurs.ActeurDto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Le prix d'achat de référence n'est renseigné que pour qui a la permission « Voir prix d'achat »
 * (EF-CAT-03) : sans elle il est absent du JSON, pas seulement masqué à l'écran (EF-ADM-12).
 */
public record ModeleDto(
		Long id,
		String marque,
		String modele,
		String version,
		String codeArticle,
		String description,
		String groupeTaxation,
		String formatChassis,
		boolean actif,
		Long prixVente,
		Long prixMinimum,
		Long prixAchatReference,
		boolean prixAchatVisible,
		List<PrixProgramme> prixProgrammes,
		Instant creeLe,
		ActeurDto creePar) {

	/** Un prix déjà saisi mais pas encore en vigueur. */
	public record PrixProgramme(TypePrix type, long valeur, LocalDate dateEffet) {
	}

}
