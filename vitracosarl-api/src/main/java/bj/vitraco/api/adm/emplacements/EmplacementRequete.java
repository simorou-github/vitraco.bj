package bj.vitraco.api.adm.emplacements;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Le code et le type ne changent plus après la création : le code entre dans la numérotation
 * des documents, et changer le type d'un emplacement déjà utilisé fausserait l'historique.
 */
public record EmplacementRequete(
		@NotBlank(message = "Le code est requis.")
		@Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9-]{1,19}$", message = "Code invalide : lettres, chiffres et tirets (2 à 20 caractères).") String code,
		@NotBlank(message = "Le nom est requis.") @Size(max = 255) String nom,
		@NotNull(message = "Le type est requis.") TypeEmplacement type,
		ModeBoutique modeBoutique,
		Long magasinRattachementId,
		@Size(max = 255) String adresse,
		@Size(max = 255) String responsableNom,
		@Size(max = 255) String email,
		@Size(max = 30) String telephone) {
}
