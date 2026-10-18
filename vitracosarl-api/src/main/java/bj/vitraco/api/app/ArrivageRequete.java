package bj.vitraco.api.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record ArrivageRequete(
		@NotBlank(message = "Le numéro de B/L est requis.") @Size(max = 100) String numeroBl,
		@Size(max = 100) String conteneur,
		@Size(max = 100) String port,
		LocalDate dateDepartPrevue,
		LocalDate dateDepartReelle,
		LocalDate dateArriveePrevue,
		LocalDate dateArriveeReelle,
		@Size(max = 1000) String observation) {
}
