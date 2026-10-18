package bj.vitraco.api.stk;

import bj.vitraco.api.adm.emplacements.PerimetreService;
import bj.vitraco.api.aud.EntreeAudit;
import bj.vitraco.api.aud.JournalAudit;
import bj.vitraco.api.cat.chassis.NumeroChassis;
import bj.vitraco.api.cat.chassis.StatutChassis;
import bj.vitraco.api.common.CodeModule;
import bj.vitraco.api.common.ErreurMetierException;
import bj.vitraco.api.common.ErreurValidationException;
import bj.vitraco.api.common.security.ActeurAuthentifie;
import bj.vitraco.api.stk.ImportStockDto.Ecart;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rapprochement du stock par pointage (reprise de données, SRS 14.2) : on pointe les châssis réellement présents dans un
 * emplacement, le système les compare à ce qu'il affiche. Un écart nul par site valide le stock d'ouverture. Le pointage ne
 * corrige rien : un écart se régularise par un ajustement validé. Le rapport est conservé comme preuve.
 */
@Service
public class RapprochementService {

	private final JdbcClient jdbc;
	private final JsonMapper json;
	private final PerimetreService perimetres;
	private final JournalAudit journal;

	RapprochementService(JdbcClient jdbc, JsonMapper json, PerimetreService perimetres, JournalAudit journal) {
		this.jdbc = jdbc;
		this.json = json;
		this.perimetres = perimetres;
		this.journal = journal;
	}

	@Transactional
	public ImportStockDto.Rapprochement comparer(Long emplacementId, List<String> numeros) {
		verifierPerimetre(emplacementId);
		Set<String> pointes = new LinkedHashSet<>();
		if (numeros != null) {
			numeros.stream().filter(n -> n != null && !n.isBlank()).map(NumeroChassis::normaliser).filter(n -> !n.isEmpty()).forEach(pointes::add);
		}
		if (pointes.isEmpty()) {
			throw new ErreurValidationException("numeros", "Aucun châssis n'a été pointé.");
		}
		String emplacement = jdbc.sql("SELECT nom FROM emplacements WHERE id = :i").param("i", emplacementId).query(String.class).optional()
				.orElseThrow(() -> new ErreurMetierException("EMPLACEMENT_INTROUVABLE", "Emplacement introuvable.", HttpStatus.NOT_FOUND));

		// Ce que le système affiche : le stock physique de l'emplacement (disponible, réservé, en préparation, bloqué).
		Map<String, String> attendus = new LinkedHashMap<>();
		jdbc.sql("SELECT numero, statut FROM chassis WHERE emplacement_id = :e AND statut IN ('DISPONIBLE', 'RESERVEE', 'EN_PREPARATION', 'BLOQUEE') ORDER BY numero")
				.param("e", emplacementId).query((rs, n) -> attendus.put(rs.getString("numero"), rs.getString("statut"))).list();

		List<Ecart> manquants = new ArrayList<>();
		attendus.forEach((numero, statut) -> {
			if (!pointes.contains(numero)) {
				manquants.add(new Ecart(numero, "Attendu au stock (" + StatutChassis.valueOf(statut).libelle() + "), non pointé"));
			}
		});
		List<Ecart> inattendus = new ArrayList<>();
		int conformes = 0;
		for (String numero : pointes) {
			if (attendus.containsKey(numero)) {
				conformes++;
				continue;
			}
			var connu = jdbc.sql("SELECT c.statut, e.nom FROM chassis c LEFT JOIN emplacements e ON e.id = c.emplacement_id WHERE c.numero = :n").param("n", numero)
					.query((rs, n) -> StatutChassis.valueOf(rs.getString("statut")).libelle() + (rs.getString("nom") == null ? " (hors stock)" : " à " + rs.getString("nom"))).optional();
			inattendus.add(new Ecart(numero, connu.map(d -> "Pointé ici mais enregistré : " + d).orElse("Pointé ici mais inconnu du système")));
		}

		Long auteur = ActeurAuthentifie.courant().map(ActeurAuthentifie::id).orElse(null);
		Long id = jdbc.sql("""
						INSERT INTO rapprochements_stock (emplacement_id, nb_attendus, nb_pointes, nb_conformes, manquants, inattendus, cree_par)
						VALUES (:e, :a, :p, :c, CAST(:m AS jsonb), CAST(:i AS jsonb), :u) RETURNING id""")
				.param("e", emplacementId).param("a", attendus.size()).param("p", pointes.size()).param("c", conformes)
				.param("m", json.writeValueAsString(manquants)).param("i", json.writeValueAsString(inattendus)).param("u", auteur).query(Long.class).single();
		journal.enregistrer(EntreeAudit.de(CodeModule.STK, "stock.rapprochement", "RapprochementStock", String.valueOf(id))
				.avecValeurs(null, Map.of("emplacement", emplacement, "attendus", attendus.size(), "pointes", pointes.size(), "manquants", manquants.size(), "inattendus", inattendus.size())));
		return detail(id);
	}

	@Transactional(readOnly = true)
	public ImportStockDto.Rapprochement detail(Long id) {
		var r = jdbc.sql("""
						SELECT r.*, e.nom AS emplacement, u.nom || coalesce(' ' || u.prenom, '') AS auteur
						FROM rapprochements_stock r JOIN emplacements e ON e.id = r.emplacement_id LEFT JOIN utilisateurs u ON u.id = r.cree_par WHERE r.id = :i""")
				.param("i", id).query((rs, n) -> {
					List<Ecart> manquants = lire(rs.getString("manquants"));
					List<Ecart> inattendus = lire(rs.getString("inattendus"));
					return new ImportStockDto.Rapprochement(rs.getLong("id"), rs.getLong("emplacement_id"), rs.getString("emplacement"), rs.getInt("nb_attendus"), rs.getInt("nb_pointes"),
							rs.getInt("nb_conformes"), manquants, inattendus, manquants.isEmpty() && inattendus.isEmpty(), rs.getString("auteur"), rs.getTimestamp("cree_le").toInstant());
				}).optional().orElseThrow(() -> new ErreurMetierException("RAPPROCHEMENT_INTROUVABLE", "Rapprochement introuvable.", HttpStatus.NOT_FOUND));
		verifierPerimetre(r.emplacementId());
		return r;
	}

	@Transactional(readOnly = true)
	public List<ImportStockDto.RapprochementListe> lister(Long emplacementId) {
		PerimetreService.Perimetre p = perimetre();
		if (!p.global() && p.emplacementIds().isEmpty()) {
			return List.of();
		}
		StringBuilder sql = new StringBuilder("""
				SELECT r.id, e.nom AS emplacement, r.nb_attendus, r.nb_pointes, r.nb_conformes, jsonb_array_length(r.manquants) AS nm, jsonb_array_length(r.inattendus) AS ni,
				       r.cree_le, u.nom || coalesce(' ' || u.prenom, '') AS auteur
				FROM rapprochements_stock r JOIN emplacements e ON e.id = r.emplacement_id LEFT JOIN utilisateurs u ON u.id = r.cree_par WHERE true""");
		Map<String, Object> params = new java.util.HashMap<>();
		if (!p.global()) {
			sql.append(" AND r.emplacement_id IN (:ids)");
			params.put("ids", p.emplacementIds());
		}
		if (emplacementId != null) {
			sql.append(" AND r.emplacement_id = :e");
			params.put("e", emplacementId);
		}
		sql.append(" ORDER BY r.cree_le DESC, r.id DESC LIMIT 100");
		return jdbc.sql(sql.toString()).params(params)
				.query((rs, n) -> new ImportStockDto.RapprochementListe(rs.getLong("id"), rs.getString("emplacement"), rs.getInt("nb_attendus"), rs.getInt("nb_pointes"),
						rs.getInt("nb_conformes"), rs.getInt("nm"), rs.getInt("ni"), rs.getInt("nm") == 0 && rs.getInt("ni") == 0, rs.getString("auteur"), rs.getTimestamp("cree_le").toInstant()))
				.list();
	}

	private List<Ecart> lire(String texte) {
		return json.readValue(texte, json.getTypeFactory().constructCollectionType(List.class, Ecart.class));
	}

	/** Un emplacement hors périmètre est introuvable pour la personne (EF-ADM-13). */
	private void verifierPerimetre(Long emplacementId) {
		PerimetreService.Perimetre p = perimetre();
		if (!p.global() && !p.autorise(emplacementId)) {
			throw new ErreurMetierException("EMPLACEMENT_INTROUVABLE", "Emplacement introuvable.", HttpStatus.NOT_FOUND);
		}
	}

	private PerimetreService.Perimetre perimetre() {
		Long utilisateur = ActeurAuthentifie.courant().map(ActeurAuthentifie::id)
				.orElseThrow(() -> new ErreurMetierException("NON_AUTHENTIFIE", "Connexion requise.", HttpStatus.UNAUTHORIZED));
		return perimetres.perimetreDe(utilisateur);
	}

}
