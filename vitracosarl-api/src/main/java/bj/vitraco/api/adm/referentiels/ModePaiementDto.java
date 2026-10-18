package bj.vitraco.api.adm.referentiels;

import bj.vitraco.api.adm.utilisateurs.ActeurDto;

import java.time.Instant;

public record ModePaiementDto(Long id, String code, String libelle, TypeDgi typeDgi, String justificatif,
							  boolean justificatifObligatoire, boolean actif, boolean systeme, Instant creeLe, ActeurDto creePar) {

	static ModePaiementDto de(ModePaiement m) {
		return new ModePaiementDto(m.getId(), m.getCode(), m.getLibelle(), m.getTypeDgi(), m.getJustificatif(),
				m.isJustificatifObligatoire(), m.isActif(), m.isSysteme(), m.getCreeLe(), ActeurDto.de(m.getCreePar()));
	}

}
