package bj.vitraco.api.stk;

import bj.vitraco.api.adm.emplacements.PerimetreService;
import bj.vitraco.api.cat.chassis.ChassisService;
import bj.vitraco.api.common.ErreurMetierException;
import bj.vitraco.api.common.security.ActeurAuthentifie;
import bj.vitraco.api.notif.DemandeNotification;
import bj.vitraco.api.notif.Notifications;
import bj.vitraco.api.stk.StockDto.Importation;
import bj.vitraco.api.stk.StockDto.Indicateurs;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Vues de stock (EF-STK-01 à 05, 10) et blocage des motos (EF-STK-06). Aucune quantité ne se saisit : tout se calcule
 * sur les châssis, dans la limite des emplacements de la personne connectée ; la Direction voit le consolidé.
 */
@Service
public class StockService {

	public record FiltreMouvements(String recherche, Long modeleId, Long emplacementId, String type, LocalDate du, LocalDate au) {
	}

	private static final String INDICATEURS = """
			count(c.id) FILTER (WHERE c.statut IN ('DISPONIBLE', 'RESERVEE', 'EN_PREPARATION', 'BLOQUEE')) AS physique,
			count(c.id) FILTER (WHERE c.statut IN ('RESERVEE', 'EN_PREPARATION')) AS reserve,
			count(c.id) FILTER (WHERE c.statut = 'BLOQUEE') AS bloque,
			count(c.id) FILTER (WHERE c.statut = 'DISPONIBLE') AS disponible,
			count(c.id) FILTER (WHERE c.statut = 'EN_TRANSIT_INTERNE') AS transit,
			count(c.id) FILTER (WHERE c.statut = 'DEPOT_VENTE') AS depot,
			count(c.id) FILTER (WHERE c.statut = 'RETOURNEE') AS retourne""";

	private final JdbcClient jdbc;
	private final PerimetreService perimetres;
	private final ChassisService chassisService;
	private final Notifications notifications;

	StockService(JdbcClient jdbc, PerimetreService perimetres, ChassisService chassisService, Notifications notifications) {
		this.jdbc = jdbc;
		this.perimetres = perimetres;
		this.chassisService = chassisService;
		this.notifications = notifications;
	}

	// ---- Synthèses -----------------------------------------------------------------------------------------------------

	/** Stock par emplacement, avec le total de la vue : global VITRACO pour la Direction, périmètre pour les autres. */
	@Transactional(readOnly = true)
	public StockDto.Synthese synthese(String type, Long modeleId) {
		PerimetreService.Perimetre p = perimetre();
		List<StockDto.LigneEmplacement> lignes = new ArrayList<>();
		if (p.global() || !p.emplacementIds().isEmpty()) {
			StringBuilder sql = new StringBuilder("SELECT e.id, e.code, e.nom, e.type, " + INDICATEURS
					+ " FROM emplacements e LEFT JOIN chassis c ON c.emplacement_id = e.id" + (modeleId == null ? "" : " AND c.modele_id = :m")
					+ " WHERE e.actif");
			Map<String, Object> params = new HashMap<>();
			if (modeleId != null) {
				params.put("m", modeleId);
			}
			if (!p.global()) {
				sql.append(" AND e.id IN (:ids)");
				params.put("ids", p.emplacementIds());
			}
			if (type != null && !type.isBlank()) {
				sql.append(" AND e.type = :t");
				params.put("t", type.toUpperCase(Locale.ROOT));
			}
			sql.append(" GROUP BY e.id, e.code, e.nom, e.type ORDER BY CASE e.type WHEN 'MAGASIN' THEN 0 WHEN 'BOUTIQUE' THEN 1 WHEN 'DEPOT_REVENDEUR' THEN 2 ELSE 3 END, e.nom");
			lignes = jdbc.sql(sql.toString()).params(params)
					.query((rs, n) -> new StockDto.LigneEmplacement(rs.getLong("id"), rs.getString("code"), rs.getString("nom"), rs.getString("type"), indicateurs(rs)))
					.list();
		}
		Indicateurs total = lignes.stream().map(StockDto.LigneEmplacement::indicateurs).reduce(Indicateurs.vide(), Indicateurs::plus);
		Importation importation = p.global() && (type == null || type.isBlank()) ? importation(modeleId) : new Importation(0, 0);
		return new StockDto.Synthese(p.global(), total, total.global(), importation, lignes);
	}

	/** Une ligne par modèle (EF-STK-03), éventuellement limitée à un emplacement ou à un type d'emplacement. */
	@Transactional(readOnly = true)
	public List<StockDto.LigneModele> parModele(Long emplacementId, String type, String recherche) {
		PerimetreService.Perimetre p = perimetre();
		if (!p.global() && p.emplacementIds().isEmpty()) {
			return List.of();
		}
		if (emplacementId != null && !p.global() && !p.autorise(emplacementId)) {
			throw new ErreurMetierException("EMPLACEMENT_INTROUVABLE", "Emplacement introuvable.", HttpStatus.NOT_FOUND);
		}
		// Les motos en cours d'importation n'ont pas d'emplacement : elles ne figurent que dans la vue consolidée.
		boolean avecImportation = p.global() && emplacementId == null && (type == null || type.isBlank());
		StringBuilder jointure = new StringBuilder(" FROM modeles m LEFT JOIN chassis c ON c.modele_id = m.id AND (c.emplacement_id IN (SELECT e.id FROM emplacements e WHERE e.actif");
		Map<String, Object> params = new HashMap<>();
		if (!p.global()) {
			jointure.append(" AND e.id IN (:ids)");
			params.put("ids", p.emplacementIds());
		}
		if (emplacementId != null) {
			jointure.append(" AND e.id = :e");
			params.put("e", emplacementId);
		}
		if (type != null && !type.isBlank()) {
			jointure.append(" AND e.type = :t");
			params.put("t", type.toUpperCase(Locale.ROOT));
		}
		jointure.append(")").append(avecImportation ? " OR c.emplacement_id IS NULL" : "").append(")");
		String ou = "";
		if (recherche != null && !recherche.isBlank()) {
			ou = " WHERE lower(m.marque || ' ' || m.nom || ' ' || m.code_article) LIKE :q";
			params.put("q", "%" + recherche.trim().toLowerCase(Locale.ROOT) + "%");
		}
		String sql = "SELECT m.id, m.marque, m.nom, m.version_modele, m.code_article, " + INDICATEURS + ","
				+ " count(c.id) FILTER (WHERE c.statut = 'EN_TRANSIT_FOURNISSEUR') AS en_transit_fournisseur,"
				+ " count(c.id) FILTER (WHERE c.statut = 'A_RECEPTIONNER') AS a_receptionner"
				+ jointure + ou + " GROUP BY m.id, m.marque, m.nom, m.version_modele, m.code_article HAVING count(c.id) > 0 ORDER BY m.marque, m.nom, m.code_article";
		return jdbc.sql(sql).params(params).query((rs, n) -> new StockDto.LigneModele(rs.getLong("id"),
				rs.getString("marque") + " " + rs.getString("nom") + (rs.getString("version_modele") == null ? "" : " " + rs.getString("version_modele")),
				rs.getString("code_article"), indicateurs(rs), new Importation(rs.getInt("en_transit_fournisseur"), rs.getInt("a_receptionner")))).list();
	}

	private Importation importation(Long modeleId) {
		return jdbc.sql("SELECT count(*) FILTER (WHERE statut = 'EN_TRANSIT_FOURNISSEUR') AS t, count(*) FILTER (WHERE statut = 'A_RECEPTIONNER') AS r FROM chassis"
						+ " WHERE emplacement_id IS NULL" + (modeleId == null ? "" : " AND modele_id = :m"))
				.params(modeleId == null ? Map.of() : Map.of("m", modeleId))
				.query((rs, n) -> new Importation(rs.getInt("t"), rs.getInt("r"))).single();
	}

	private static Indicateurs indicateurs(ResultSet rs) throws SQLException {
		return new Indicateurs(rs.getInt("physique"), rs.getInt("reserve"), rs.getInt("bloque"), rs.getInt("disponible"), rs.getInt("transit"),
				rs.getInt("depot"), rs.getInt("retourne"));
	}

	// ---- Châssis bloqués -----------------------------------------------------------------------------------------------

	@Transactional(readOnly = true)
	public Page<StockDto.Bloque> bloques(Long emplacementId, String recherche, Pageable pagination) {
		PerimetreService.Perimetre p = perimetre();
		if (!p.global() && p.emplacementIds().isEmpty()) {
			return new PageImpl<>(List.of(), pagination, 0);
		}
		List<String> criteres = new ArrayList<>(List.of("c.statut = 'BLOQUEE'"));
		Map<String, Object> params = new HashMap<>();
		if (!p.global()) {
			criteres.add("c.emplacement_id IN (:ids)");
			params.put("ids", p.emplacementIds());
		}
		if (emplacementId != null) {
			criteres.add("c.emplacement_id = :e");
			params.put("e", emplacementId);
		}
		if (recherche != null && !recherche.isBlank()) {
			criteres.add("(c.numero LIKE :q OR lower(m.code_article) LIKE :ql)");
			params.put("q", "%" + recherche.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "") + "%");
			params.put("ql", "%" + recherche.trim().toLowerCase(Locale.ROOT) + "%");
		}
		String base = " FROM chassis c JOIN modeles m ON m.id = c.modele_id LEFT JOIN emplacements e ON e.id = c.emplacement_id"
				+ " LEFT JOIN LATERAL (SELECT x.cree_le, x.motif, x.utilisateur_libelle FROM mouvements_stock x WHERE x.chassis_id = c.id AND x.statut_apres = 'BLOQUEE'"
				+ " ORDER BY x.cree_le DESC, x.id DESC LIMIT 1) dernier ON true WHERE " + String.join(" AND ", criteres);
		long total = jdbc.sql("SELECT count(*)" + base).params(params).query(Long.class).single();
		var contenu = jdbc.sql("SELECT c.id, c.numero, m.marque, m.nom, m.code_article, e.id AS eid, e.nom AS enom, dernier.cree_le, dernier.motif, dernier.utilisateur_libelle" + base
						+ " ORDER BY dernier.cree_le ASC NULLS LAST, c.id LIMIT :limite OFFSET :decalage")
				.params(params).param("limite", pagination.getPageSize()).param("decalage", pagination.getOffset())
				.query((rs, n) -> {
					Instant depuis = rs.getTimestamp("cree_le") == null ? null : rs.getTimestamp("cree_le").toInstant();
					return new StockDto.Bloque(rs.getLong("id"), rs.getString("numero"), rs.getString("marque") + " " + rs.getString("nom"), rs.getString("code_article"),
							(Long) rs.getObject("eid"), rs.getString("enom"), depuis, rs.getString("motif"), rs.getString("utilisateur_libelle"),
							depuis == null ? 0 : ChronoUnit.DAYS.between(depuis, Instant.now()));
				}).list();
		return new PageImpl<>(contenu, pagination, total);
	}

	// ---- Journal des mouvements ----------------------------------------------------------------------------------------

	/** Journal des mouvements par châssis, modèle, emplacement, type et période (EF-STK-05). */
	@Transactional(readOnly = true)
	public Page<StockDto.Mouvement> mouvements(FiltreMouvements filtre, Pageable pagination) {
		PerimetreService.Perimetre p = perimetre();
		List<String> criteres = new ArrayList<>();
		Map<String, Object> params = new HashMap<>();
		if (!p.global()) {
			if (p.emplacementIds().isEmpty()) {
				return new PageImpl<>(List.of(), pagination, 0);
			}
			criteres.add("(m.emplacement_source_id IN (:ids) OR m.emplacement_destination_id IN (:ids))");
			params.put("ids", p.emplacementIds());
		}
		if (filtre.recherche() != null && !filtre.recherche().isBlank()) {
			criteres.add("c.numero LIKE :q");
			params.put("q", "%" + filtre.recherche().trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "") + "%");
		}
		if (filtre.modeleId() != null) {
			criteres.add("c.modele_id = :mo");
			params.put("mo", filtre.modeleId());
		}
		if (filtre.emplacementId() != null) {
			criteres.add("(m.emplacement_source_id = :em OR m.emplacement_destination_id = :em)");
			params.put("em", filtre.emplacementId());
		}
		if (filtre.type() != null && !filtre.type().isBlank()) {
			criteres.add("m.type = :t");
			params.put("t", filtre.type().toUpperCase(Locale.ROOT));
		}
		if (filtre.du() != null) {
			criteres.add("m.cree_le >= :du");
			params.put("du", filtre.du().atStartOfDay().toInstant(ZoneOffset.UTC).atOffset(ZoneOffset.UTC));
		}
		if (filtre.au() != null) {
			criteres.add("m.cree_le < :au");
			params.put("au", filtre.au().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).atOffset(ZoneOffset.UTC));
		}
		String ou = criteres.isEmpty() ? "" : " WHERE " + String.join(" AND ", criteres);
		String base = " FROM mouvements_stock m JOIN chassis c ON c.id = m.chassis_id JOIN modeles mo ON mo.id = c.modele_id"
				+ " LEFT JOIN emplacements s ON s.id = m.emplacement_source_id LEFT JOIN emplacements d ON d.id = m.emplacement_destination_id" + ou;
		long total = jdbc.sql("SELECT count(*)" + base).params(params).query(Long.class).single();
		var contenu = jdbc.sql("SELECT m.id, m.cree_le, m.type, c.id AS chassis_id, c.numero, mo.marque, mo.nom, mo.code_article, s.nom AS source, d.nom AS destination,"
						+ " m.statut_avant, m.statut_apres, m.document_type, m.document_id, m.motif, m.utilisateur_libelle" + base
						+ " ORDER BY m.cree_le DESC, m.id DESC LIMIT :limite OFFSET :decalage")
				.params(params).param("limite", pagination.getPageSize()).param("decalage", pagination.getOffset())
				.query((rs, n) -> new StockDto.Mouvement(rs.getLong("id"), rs.getTimestamp("cree_le").toInstant(), rs.getString("type"), rs.getString("numero"),
						rs.getLong("chassis_id"), rs.getString("marque") + " " + rs.getString("nom"), rs.getString("source"), rs.getString("destination"),
						rs.getString("statut_avant"), rs.getString("statut_apres"), rs.getString("document_type"), (Long) rs.getObject("document_id"),
						rs.getString("motif"), rs.getString("utilisateur_libelle")))
				.list();
		return new PageImpl<>(contenu, pagination, total);
	}

	// ---- Blocage -------------------------------------------------------------------------------------------------------

	@Transactional
	public void bloquer(Long chassisId, BlocageRequete requete) {
		String numero = verifierPerimetre(chassisId);
		chassisService.bloquer(chassisId, requete.motif().trim(), requete.piece().trim());
		notifier("stock.chassis_bloque", "Châssis bloqué : " + numero, " a bloqué le châssis " + numero + ". Motif : " + requete.motif().trim()
				+ " (pièce : " + requete.piece().trim() + ").", chassisId);
	}

	@Transactional
	public void debloquer(Long chassisId, DeblocageRequete requete) {
		String numero = verifierPerimetre(chassisId);
		chassisService.debloquer(chassisId, requete.motif().trim());
		notifier("stock.chassis_debloque", "Châssis débloqué : " + numero, " a débloqué le châssis " + numero + ". Motif : " + requete.motif().trim() + ".", chassisId);
	}

	private void notifier(String type, String titre, String corps, Long chassisId) {
		String auteur = ActeurAuthentifie.courant().map(ActeurAuthentifie::libelle).orElse("Le système");
		notifications.notifierRole("controleur", DemandeNotification.de(type, titre, auteur + corps, "/catalogue/chassis/" + chassisId));
	}

	/** Un châssis hors de ses emplacements est introuvable pour la personne (EF-ADM-13). */
	private String verifierPerimetre(Long chassisId) {
		PerimetreService.Perimetre p = perimetre();
		var ligne = jdbc.sql("SELECT numero, emplacement_id FROM chassis WHERE id = :i").param("i", chassisId)
				.query((rs, n) -> Map.of("numero", rs.getString("numero"), "emplacement", (Object) (rs.getObject("emplacement_id") == null ? -1L : rs.getLong("emplacement_id")))).optional();
		if (ligne.isEmpty() || (!p.global() && !p.autorise((Long) ligne.get().get("emplacement")))) {
			throw new ErreurMetierException("CHASSIS_INTROUVABLE", "Châssis introuvable.", HttpStatus.NOT_FOUND);
		}
		return (String) ligne.get().get("numero");
	}

	private PerimetreService.Perimetre perimetre() {
		Long utilisateur = ActeurAuthentifie.courant().map(ActeurAuthentifie::id)
				.orElseThrow(() -> new ErreurMetierException("NON_AUTHENTIFIE", "Connexion requise.", HttpStatus.UNAUTHORIZED));
		return perimetres.perimetreDe(utilisateur);
	}

}
