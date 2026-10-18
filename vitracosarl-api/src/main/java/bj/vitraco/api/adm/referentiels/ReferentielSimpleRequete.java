package bj.vitraco.api.adm.referentiels;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Le code sert de clé aux autres modules : il ne change plus après la création. */
public record ReferentielSimpleRequete(
		@NotBlank(message = "Le code est requis.")
		@Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{1,29}$", message = "Code invalide : lettres, chiffres, tirets et underscores (2 à 30 caractères).") String code,
		@NotBlank(message = "Le libellé est requis.") @Size(max = 255, message = "255 caractères au plus.") String libelle) {
}
