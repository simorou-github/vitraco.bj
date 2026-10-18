package bj.vitraco.api.app;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Un frais d'importation. Sans libellé, le type en tient lieu ; sans date, c'est aujourd'hui ; sans pièce, le frais est
 * signalé (il reste saisissable : la pièce arrive souvent après). Le taux est facultatif en francs CFA (XOF).
 */
public record FraisRequete(
		@NotNull(message = "Le type de frais est requis.") TypeFrais type,
		@Size(max = 255) String libelle,
		@NotNull(message = "Le montant est requis.") @DecimalMin(value = "0", message = "Le montant ne peut pas être négatif.") BigDecimal montant,
		@NotNull(message = "La devise est requise.") @Pattern(regexp = "^[A-Za-z]{3}$", message = "Code devise sur 3 lettres (par exemple USD, EUR, XOF).") String devise,
		@DecimalMin(value = "0.000001", message = "Le taux de change doit être positif.") BigDecimal tauxChange,
		@Size(max = 150) String referencePiece,
		LocalDate dateFrais) {
}
