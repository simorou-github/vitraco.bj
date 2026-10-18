package bj.vitraco.api.adm.parametres;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** Sans date d'effet, la valeur s'applique dès aujourd'hui. Une date passée est refusée : l'historique ne se réécrit pas. */
public record ParametreRequete(
		@NotBlank(message = "La valeur est requise.") @Size(max = 500) String valeur,
		LocalDate dateEffet) {
}
