package bj.vitraco.api.adm.emplacements;

import bj.vitraco.api.adm.utilisateurs.ActeurDto;

import java.time.Instant;

public record EmplacementDto(
		Long id,
		String code,
		String nom,
		TypeEmplacement type,
		ModeBoutique modeBoutique,
		EmplacementRef magasinRattachement,
		String adresse,
		String responsableNom,
		String email,
		String telephone,
		boolean actif,
		boolean systeme,
		Instant creeLe,
		ActeurDto creePar) {

	public static EmplacementDto de(Emplacement e) {
		return new EmplacementDto(e.getId(), e.getCode(), e.getNom(), e.getType(), e.getModeBoutique(),
				EmplacementRef.de(e.getMagasinRattachement()), e.getAdresse(), e.getResponsableNom(), e.getEmail(),
				e.getTelephone(), e.isActif(), e.isSysteme(), e.getCreeLe(), ActeurDto.de(e.getCreePar()));
	}

}
