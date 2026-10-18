package bj.vitraco.api.cat;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Un ou plusieurs prix changent à la même date, ce qui permet de baisser le prix de vente et le
 * prix minimum ensemble. Sans date d'effet, le changement vaut dès aujourd'hui ; une date passée est refusée.
 */
public record ChangementPrixRequete(
		@Min(value = 0, message = "Le prix ne peut pas être négatif.") Long prixVente,
		@Min(value = 0, message = "Le prix ne peut pas être négatif.") Long prixMinimum,
		@Min(value = 0, message = "Le prix ne peut pas être négatif.") Long prixAchatReference,
		LocalDate dateEffet,
		@Size(max = 500) String motif) {
}
