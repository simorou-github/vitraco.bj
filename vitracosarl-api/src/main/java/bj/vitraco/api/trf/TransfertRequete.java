package bj.vitraco.api.trf;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Création ou modification d'un transfert : la liste de châssis est obligatoire (EF-TRF-01). */
public record TransfertRequete(
		@NotNull(message = "L'emplacement source est requis.") Long sourceId,
		@NotNull(message = "L'emplacement de destination est requis.") Long destinationId,
		@Size(max = 1000) String observation,
		List<@Size(max = 60) String> numeros,
		/** Le transfert que celui-ci corrige (transfert retour). */
		Long retourDeId) {
}
