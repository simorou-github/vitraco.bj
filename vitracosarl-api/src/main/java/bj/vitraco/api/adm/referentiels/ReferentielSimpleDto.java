package bj.vitraco.api.adm.referentiels;

import bj.vitraco.api.adm.utilisateurs.ActeurDto;

import java.time.Instant;

public record ReferentielSimpleDto(Long id, String code, String libelle, boolean actif, Instant creeLe, ActeurDto creePar) {

	static ReferentielSimpleDto de(ReferentielSimple r) {
		return new ReferentielSimpleDto(r.getId(), r.getCode(), r.getLibelle(), r.isActif(), r.getCreeLe(), ActeurDto.de(r.getCreePar()));
	}

}
