package bj.vitraco.api.adm.referentiels;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Le code ne change plus après la création. Pour un mode livré avec l'application, le type DGI
 * est figé aussi : les autres modules et la facturation s'y appuient.
 */
public record ModePaiementRequete(
		@NotBlank(message = "Le code est requis.")
		@Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{1,29}$", message = "Code invalide : lettres, chiffres, tirets et underscores (2 à 30 caractères).") String code,
		@NotBlank(message = "Le libellé est requis.") @Size(max = 255) String libelle,
		@NotNull(message = "Le type DGI est requis.") TypeDgi typeDgi,
		@Size(max = 255) String justificatif,
		Boolean justificatifObligatoire) {
}
