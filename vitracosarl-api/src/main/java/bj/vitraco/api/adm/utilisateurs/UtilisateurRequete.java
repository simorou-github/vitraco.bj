package bj.vitraco.api.adm.utilisateurs;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UtilisateurRequete(
		@NotBlank(message = "Le nom est requis.") @Size(max = 255) String nom,
		@Size(max = 255) String prenom,
		@NotBlank(message = "L'adresse e-mail est requise.") @Email(message = "Adresse e-mail invalide.") @Size(max = 255) String email,
		@Size(max = 30) String telephone,
		@Size(max = 255) String adresse,
		@NotEmpty(message = "Au moins un rôle est requis.") List<String> roles,
		List<Long> emplacements) {
}
