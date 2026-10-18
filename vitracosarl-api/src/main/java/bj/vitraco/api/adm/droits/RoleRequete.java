package bj.vitraco.api.adm.droits;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Le code ne se change pas après la création : il sert de clé aux droits et aux utilisateurs. */
public record RoleRequete(
		@Pattern(regexp = "^[a-z][a-z0-9_]{2,59}$", message = "Code invalide : minuscules, chiffres et _ (3 à 60 caractères).") String code,
		@NotBlank(message = "Le libellé est requis.") @Size(max = 255) String libelle,
		@Size(max = 1000) String description,
		@NotNull(message = "La liste des permissions est requise.") List<String> permissions,
		Boolean perimetreGlobal,
		Boolean doubleAuthentificationRequise) {
}
