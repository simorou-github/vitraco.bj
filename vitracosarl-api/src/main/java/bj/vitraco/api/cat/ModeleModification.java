package bj.vitraco.api.cat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Fiche descriptive d'un modèle. Les prix se changent à part, avec une date d'effet. */
public record ModeleModification(
		@NotBlank(message = "La marque est requise.") @Size(max = 100) String marque,
		@NotBlank(message = "Le modèle est requis.") @Size(max = 255) String modele,
		@Size(max = 100) String version,
		String description,
		@Pattern(regexp = "^[A-Fa-f]$", message = "Groupe de taxation de A à F.") String groupeTaxation,
		@Size(max = 255) String formatChassis) {
}
