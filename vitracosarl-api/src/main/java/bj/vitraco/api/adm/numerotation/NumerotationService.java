package bj.vitraco.api.adm.numerotation;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Year;

/**
 * Numérote chaque document par type, emplacement et année, sans trou ni doublon
 * (EF-ADM-07). Format : {@code FV-BTQ1-2027-000123}, ou {@code PO-2027-000045}
 * pour un document sans emplacement.
 *
 * <p>Doit être appelé dans la transaction qui crée le document : le verrou de ligne
 * est alors tenu jusqu'à la validation, et une annulation rend le numéro.
 */
@Service
public class NumerotationService {

	private final JdbcClient jdbc;
	private final Clock horloge;

	NumerotationService(JdbcClient jdbc, Clock horloge) {
		this.jdbc = jdbc;
		this.horloge = horloge;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public String prochainNumero(TypeDocument type, String codeEmplacement) {
		int annee = Year.now(horloge).getValue();
		String emplacement = codeEmplacement == null ? "" : codeEmplacement;

		long valeur = jdbc.sql("""
						INSERT INTO sequences_numerotation (type_document, emplacement, annee, derniere_valeur)
						VALUES (:type, :emplacement, :annee, 1)
						ON CONFLICT (type_document, emplacement, annee)
						DO UPDATE SET derniere_valeur = sequences_numerotation.derniere_valeur + 1
						RETURNING derniere_valeur
						""")
				.param("type", type.name())
				.param("emplacement", emplacement)
				.param("annee", annee)
				.query(Long.class)
				.single();

		String segmentEmplacement = emplacement.isEmpty() ? "" : emplacement + "-";
		return "%s-%s%d-%06d".formatted(type.prefixe(), segmentEmplacement, annee, valeur);
	}

}
