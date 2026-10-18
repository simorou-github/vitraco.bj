package bj.vitraco.api.stk;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Blocage d'une moto : un motif et la référence de la pièce justificative (litige, PV, courrier). */
public record BlocageRequete(
		@NotBlank(message = "Le motif est requis.") @Size(max = 400) String motif,
		@NotBlank(message = "La référence de la pièce est requise.") @Size(max = 80) String piece) {
}
