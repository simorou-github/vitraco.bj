package bj.vitraco.api.adm.referentiels;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record FournisseurRequete(
		@NotBlank(message = "La raison sociale est requise.") @Size(max = 255) String raisonSociale,
		@NotBlank(message = "Le pays est requis.") @Size(max = 100) String pays,
		@NotBlank(message = "La devise est requise.")
		@Pattern(regexp = "^[A-Za-z]{3}$", message = "Code devise sur 3 lettres (par exemple USD, EUR, XOF).") String devise,
		@Valid List<ContactRequete> contacts) {

	public record ContactRequete(
			@NotBlank(message = "Le nom du contact est requis.") @Size(max = 255) String nom,
			@Size(max = 255) String fonction,
			@Email(message = "Adresse e-mail invalide.") @Size(max = 255) String email,
			@Size(max = 30) String telephone) {
	}

}
