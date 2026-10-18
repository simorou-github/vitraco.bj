package bj.vitraco.api.notif;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Boîte de notifications d'un utilisateur : on ne lit et on ne marque jamais que les siennes. */
@Service
class NotificationLecture {

	private final JdbcClient jdbc;

	NotificationLecture(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	Page<NotificationDto> lister(Long utilisateurId, boolean nonLuesSeulement, Pageable pagination) {
		String filtre = nonLuesSeulement ? " AND lue_le IS NULL" : "";
		long total = jdbc.sql("SELECT count(*) FROM notifications WHERE destinataire_id = :u" + filtre)
				.param("u", utilisateurId).query(Long.class).single();
		var contenu = jdbc.sql("SELECT id, type, titre, message, lien, lue_le IS NOT NULL AS lue, cree_le "
						+ "FROM notifications WHERE destinataire_id = :u" + filtre
						+ " ORDER BY cree_le DESC, id DESC LIMIT :limite OFFSET :decalage")
				.param("u", utilisateurId)
				.param("limite", pagination.getPageSize())
				.param("decalage", pagination.getOffset())
				.query((rs, n) -> new NotificationDto(rs.getLong("id"), rs.getString("type"), rs.getString("titre"),
						rs.getString("message"), rs.getString("lien"), rs.getBoolean("lue"), rs.getTimestamp("cree_le").toInstant()))
				.list();
		return new PageImpl<>(contenu, pagination, total);
	}

	@Transactional(readOnly = true)
	long nonLues(Long utilisateurId) {
		return jdbc.sql("SELECT count(*) FROM notifications WHERE destinataire_id = :u AND lue_le IS NULL")
				.param("u", utilisateurId).query(Long.class).single();
	}

	@Transactional
	void marquerLue(Long utilisateurId, Long id) {
		jdbc.sql("UPDATE notifications SET lue_le = now() WHERE id = :id AND destinataire_id = :u AND lue_le IS NULL")
				.param("id", id).param("u", utilisateurId).update();
	}

	@Transactional
	void toutMarquerLu(Long utilisateurId) {
		jdbc.sql("UPDATE notifications SET lue_le = now() WHERE destinataire_id = :u AND lue_le IS NULL")
				.param("u", utilisateurId).update();
	}

}
