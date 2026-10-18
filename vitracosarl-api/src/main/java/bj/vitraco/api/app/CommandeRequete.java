package bj.vitraco.api.app;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Création ou modification d'une commande (tant qu'elle est « commandée »). Le numéro est attribué par le système.
 * Sans date, la commande porte celle du jour ; le taux est facultatif pour une commande déjà en francs CFA (XOF).
 */
public record CommandeRequete(
		@NotNull(message = "Le fournisseur est requis.") Long fournisseurId,
		LocalDate dateCommande,
		@Pattern(regexp = "^[A-Za-z]{3}$", message = "Code devise sur 3 lettres (par exemple USD, EUR, XOF).") String devise,
		@DecimalMin(value = "0.000001", message = "Le taux de change doit être positif.") BigDecimal tauxChange,
		@NotNull(message = "L'Incoterm est requis.") Incoterm incoterm,
		@NotNull(message = "La base de prix (FOB ou EXW) est requise.") BasePrix basePrix,
		@Size(max = 1000) String observation,
		@NotEmpty(message = "Ajoutez au moins un modèle à la commande.") @Valid List<LigneRequete> lignes) {

	public record LigneRequete(
			@NotNull(message = "Le modèle est requis.") Long modeleId,
			@NotNull(message = "La quantité est requise.") @Min(value = 1, message = "La quantité doit être d'au moins 1.")
			@Max(value = 100000, message = "Quantité excessive.") Integer quantite,
			@NotNull(message = "Le prix unitaire est requis.") @DecimalMin(value = "0", message = "Le prix ne peut pas être négatif.") BigDecimal prixUnitaire) {
	}

}
