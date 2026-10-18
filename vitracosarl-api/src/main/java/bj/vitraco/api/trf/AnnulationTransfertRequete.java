package bj.vitraco.api.trf;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AnnulationTransfertRequete(@NotBlank(message = "Le motif est requis.") @Size(max = 500) String motif) {
}
