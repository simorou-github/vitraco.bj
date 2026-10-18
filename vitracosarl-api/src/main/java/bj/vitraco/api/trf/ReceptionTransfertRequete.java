package bj.vitraco.api.trf;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** Les châssis pointés à l'arrivée. Avec {@code cloturerAvecEcart}, ceux qui manquent ouvrent l'anomalie A12. */
public record ReceptionTransfertRequete(
		@NotEmpty(message = "Aucun châssis n'a été pointé.") List<String> numeros,
		Boolean cloturerAvecEcart) {
}
