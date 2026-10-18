package bj.vitraco.api.rec;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Un châssis scanné ou saisi. Pour une moto annoncée par le colisage, le modèle est connu ; sinon le code article est
 * requis. Une moto endommagée ou non annoncée exige un motif.
 */
public record ScanRequete(
		@NotBlank(message = "Le numéro de châssis est requis.") @Size(max = 60) String numero,
		@Size(max = 50) String codeArticle,
		Boolean endommage,
		@Size(max = 500) String motif) {
}
