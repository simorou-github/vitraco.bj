package bj.vitraco.api.rec;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReceptionRequete(
		@NotNull(message = "L'arrivage est requis.") Long arrivageId,
		@NotNull(message = "Le magasin de réception est requis.") Long emplacementId,
		@Size(max = 1000) String observation) {
}
