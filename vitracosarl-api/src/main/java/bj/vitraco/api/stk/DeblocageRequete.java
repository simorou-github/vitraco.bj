package bj.vitraco.api.stk;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeblocageRequete(@NotBlank(message = "Le motif est requis.") @Size(max = 400) String motif) {
}
