package bj.vitraco.api.cat.chassis;

import bj.vitraco.api.common.security.ActeurAuthentifie;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Journal des mouvements d'un châssis, en ajout seul : à chaque changement de statut ou d'emplacement, une ligne. */
@Component
public class MouvementsStock {

	public record Evenement(Long id, String type, Long emplacementSourceId, String emplacementSource, Long emplacementDestinationId,
							String emplacementDestination, StatutChassis statutAvant, StatutChassis statutApres, String documentType,
							Long documentId, String motif, String auteur, Instant date) {
	}

	private final JdbcClient jdbc;

	MouvementsStock(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void enregistrer(Long chassisId, String type, Long source, Long destination, StatutChassis avant, StatutChassis apres,
							String documentType, Long documentId, String motif) {
		var acteur = ActeurAuthentifie.courant();
		jdbc.sql("""
						INSERT INTO mouvements_stock (chassis_id, type, emplacement_source_id, emplacement_destination_id, statut_avant, statut_apres,
						                              document_type, document_id, motif, utilisateur_id, utilisateur_libelle)
						VALUES (:c, :t, :s, :d, :av, :ap, :dt, :di, :m, :u, :ul)""")
				.param("c", chassisId).param("t", type).param("s", source).param("d", destination)
				.param("av", avant == null ? null : avant.name()).param("ap", apres.name())
				.param("dt", documentType).param("di", documentId).param("m", motif)
				.param("u", acteur.map(ActeurAuthentifie::id).orElse(null))
				.param("ul", acteur.map(ActeurAuthentifie::libelle).orElse(null))
				.update();
	}

	@Transactional(readOnly = true)
	public List<Evenement> historique(Long chassisId) {
		return jdbc.sql("""
						SELECT m.id, m.type, m.emplacement_source_id, s.nom AS source, m.emplacement_destination_id, d.nom AS destination,
						       m.statut_avant, m.statut_apres, m.document_type, m.document_id, m.motif, m.utilisateur_libelle, m.cree_le
						FROM mouvements_stock m
						LEFT JOIN emplacements s ON s.id = m.emplacement_source_id
						LEFT JOIN emplacements d ON d.id = m.emplacement_destination_id
						WHERE m.chassis_id = :c ORDER BY m.cree_le, m.id""")
				.param("c", chassisId)
				.query((rs, n) -> new Evenement(rs.getLong("id"), rs.getString("type"), (Long) rs.getObject("emplacement_source_id"),
						rs.getString("source"), (Long) rs.getObject("emplacement_destination_id"), rs.getString("destination"),
						rs.getString("statut_avant") == null ? null : StatutChassis.valueOf(rs.getString("statut_avant")),
						StatutChassis.valueOf(rs.getString("statut_apres")), rs.getString("document_type"), (Long) rs.getObject("document_id"),
						rs.getString("motif"), rs.getString("utilisateur_libelle"), rs.getTimestamp("cree_le").toInstant()))
				.list();
	}

}
