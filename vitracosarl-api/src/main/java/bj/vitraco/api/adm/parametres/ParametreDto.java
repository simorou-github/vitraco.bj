package bj.vitraco.api.adm.parametres;

import bj.vitraco.api.adm.utilisateurs.ActeurDto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Un paramètre tel que le voit l'administrateur : sa valeur en vigueur et, s'il y en a une, la prochaine. */
public record ParametreDto(String cle, GroupeParametre groupe, String libelle, String aide, TypeParametre type, String unite,
						   BigDecimal min, BigDecimal max, String defaut, boolean renseigne, String valeur, LocalDate dateEffet,
						   Prochaine prochaine, ActeurDto modifiePar, Instant modifieLe) {

	public record Prochaine(String valeur, LocalDate dateEffet) {
	}

	public record Version(String valeur, LocalDate dateEffet, ActeurDto creePar, Instant creeLe) {

		static Version de(Parametre p) {
			return new Version(p.getValeur(), p.getDateEffet(), ActeurDto.de(p.getCreePar()), p.getCreeLe());
		}

	}

}
