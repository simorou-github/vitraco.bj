package bj.vitraco.api.adm.referentiels;

import bj.vitraco.api.adm.utilisateurs.ActeurDto;

import java.time.Instant;
import java.util.List;

public record FournisseurDto(Long id, String raisonSociale, String pays, String devise, List<Contact> contacts,
							 boolean actif, Instant creeLe, ActeurDto creePar) {

	public record Contact(String nom, String fonction, String email, String telephone) {
	}

	static FournisseurDto de(Fournisseur f) {
		return new FournisseurDto(f.getId(), f.getRaisonSociale(), f.getPays(), f.getDevise(),
				f.getContacts().stream().map(c -> new Contact(c.getNom(), c.getFonction(), c.getEmail(), c.getTelephone())).toList(),
				f.isActif(), f.getCreeLe(), ActeurDto.de(f.getCreePar()));
	}

}
