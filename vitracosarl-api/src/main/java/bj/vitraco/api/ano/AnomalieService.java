package bj.vitraco.api.ano;

import bj.vitraco.api.adm.numerotation.NumerotationService;
import bj.vitraco.api.adm.numerotation.TypeDocument;
import bj.vitraco.api.aud.EntreeAudit;
import bj.vitraco.api.aud.JournalAudit;
import bj.vitraco.api.common.CodeModule;
import bj.vitraco.api.common.security.ActeurAuthentifie;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * Ouverture des anomalies de contrôle (SRS 7.5) depuis les modules métier. Le traitement (justification, régularisation,
 * clôture) viendra avec le module ANO ; pour l'instant l'anomalie existe, numérotée, avec son objet et son exposition.
 */
@Service
public class AnomalieService {

	private final JdbcClient jdbc;
	private final NumerotationService numerotation;
	private final JournalAudit journal;

	AnomalieService(JdbcClient jdbc, NumerotationService numerotation, JournalAudit journal) {
		this.jdbc = jdbc;
		this.numerotation = numerotation;
		this.journal = journal;
	}

	/**
	 * Ouvre l'anomalie, ou met à jour celle qui est déjà vivante pour ce code et cet objet.
	 *
	 * @return le numéro de l'anomalie
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public String ouvrir(String code, String objetType, Long objetId, String titre, String description, Long montantFcfa) {
		Optional<String> existante = jdbc.sql("SELECT numero FROM anomalies WHERE code = :c AND objet_type = :t AND objet_id = :i AND statut IN ('OUVERTE', 'EN_COURS')")
				.param("c", code).param("t", objetType).param("i", objetId).query(String.class).optional();
		if (existante.isPresent()) {
			jdbc.sql("UPDATE anomalies SET titre = :ti, description = :d, montant_fcfa = :m WHERE numero = :n")
					.param("ti", titre).param("d", description).param("m", montantFcfa).param("n", existante.get()).update();
			return existante.get();
		}
		String numero = numerotation.prochainNumero(TypeDocument.ANOMALIE, null);
		Long acteur = ActeurAuthentifie.courant().map(ActeurAuthentifie::id).orElse(null);
		Long id = jdbc.sql("""
						INSERT INTO anomalies (numero, code, objet_type, objet_id, titre, description, montant_fcfa, ouverte_par)
						VALUES (:n, :c, :t, :i, :ti, :d, :m, :u) RETURNING id""")
				.param("n", numero).param("c", code).param("t", objetType).param("i", objetId).param("ti", titre).param("d", description)
				.param("m", montantFcfa).param("u", acteur).query(Long.class).single();
		journal.enregistrer(EntreeAudit.de(CodeModule.ANO, "anomalie.ouverte", "Anomalie", String.valueOf(id))
				.avecValeurs(null, Map.of("numero", numero, "code", code, "objet", objetType + " " + objetId)).avecMotif(titre));
		return numero;
	}

}
