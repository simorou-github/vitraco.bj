package bj.vitraco.api.cat.chassis;

import bj.vitraco.api.adm.emplacements.EmplacementRef;

import java.time.LocalDate;

/** Le coût de revient n'est envoyé qu'avec la permission « Voir prix d'achat » (EF-CAT-03). */
public record ChassisDto(
		Long id,
		String numero,
		Long modeleId,
		String modele,
		String codeArticle,
		StatutChassis statut,
		String statutLibelle,
		EmplacementRef emplacement,
		LocalDate dateEntree,
		Long ancienneteJours,
		Long coutRevient,
		boolean coutVisible,
		String couleur,
		String numeroMoteur,
		Long arrivageId) {
}
