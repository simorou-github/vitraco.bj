package bj.vitraco.api.cat;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Création d'un modèle avec ses prix initiaux, en vigueur dès aujourd'hui. Le code article ne change plus ensuite. */
public record ModeleRequete(
		@NotBlank(message = "La marque est requise.") @Size(max = 100) String marque,
		@NotBlank(message = "Le modèle est requis.") @Size(max = 255) String modele,
		@Size(max = 100) String version,
		@NotBlank(message = "Le code article est requis.")
		@Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._/-]{1,49}$", message = "Code article invalide : lettres, chiffres, point, tiret, barre oblique (2 à 50 caractères).") String codeArticle,
		String description,
		@Pattern(regexp = "^[A-Fa-f]$", message = "Groupe de taxation de A à F.") String groupeTaxation,
		@Size(max = 255) String formatChassis,
		@NotNull(message = "Le prix de vente est requis.") @Min(value = 0, message = "Le prix ne peut pas être négatif.") Long prixVente,
		@NotNull(message = "Le prix minimum est requis.") @Min(value = 0, message = "Le prix ne peut pas être négatif.") Long prixMinimum,
		@Min(value = 0, message = "Le prix ne peut pas être négatif.") Long prixAchatReference) {
}
