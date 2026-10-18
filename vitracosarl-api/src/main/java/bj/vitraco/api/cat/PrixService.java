package bj.vitraco.api.cat;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Historique des prix d'un modèle (EF-CAT-02). Les autres modules lisent le prix à une date par
 * {@link #enVigueur} ou {@link #prixVenteAu} : une commande validée garde ainsi le prix de sa date,
 * quels que soient les changements ultérieurs.
 */
@Service
public class PrixService {

	/** Prix en vigueur à une date, en francs CFA ; {@code null} quand le prix n'a jamais été fixé. */
	public record PrixEnVigueur(Long vente, Long minimum, Long achatReference) {

		static final PrixEnVigueur VIDE = new PrixEnVigueur(null, null, null);

	}

	/** Une ligne de l'historique. */
	public record LignePrix(Long id, TypePrix type, long valeur, LocalDate dateEffet, String motif, Instant creeLe, Long creePar,
							String creeParLibelle) {
	}

	public record ChangementPrix(TypePrix type, long valeur) {
	}

	private final JdbcClient jdbc;
	private final Clock horloge;

	PrixService(JdbcClient jdbc, Clock horloge) {
		this.jdbc = jdbc;
		this.horloge = horloge;
	}

	@Transactional(readOnly = true)
	public Optional<Long> prixVenteAu(Long modeleId, LocalDate date) {
		return Optional.ofNullable(enVigueur(List.of(modeleId), date).getOrDefault(modeleId, PrixEnVigueur.VIDE).vente());
	}

	/** Prix en vigueur à {@code date} pour chaque modèle demandé ; un modèle sans prix est absent du résultat. */
	@Transactional(readOnly = true)
	public Map<Long, PrixEnVigueur> enVigueur(Collection<Long> modeleIds, LocalDate date) {
		if (modeleIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, Map<TypePrix, Long>> parModele = new HashMap<>();
		jdbc.sql("""
						SELECT DISTINCT ON (modele_id, type_prix) modele_id, type_prix, valeur
						FROM prix_historique
						WHERE modele_id IN (:ids) AND date_effet <= :date
						ORDER BY modele_id, type_prix, date_effet DESC, id DESC""")
				.param("ids", modeleIds).param("date", date)
				.query((rs, n) -> {
					parModele.computeIfAbsent(rs.getLong("modele_id"), k -> new HashMap<>())
							.put(TypePrix.valueOf(rs.getString("type_prix")), rs.getLong("valeur"));
					return null;
				}).list();
		Map<Long, PrixEnVigueur> resultat = new HashMap<>();
		parModele.forEach((id, prix) -> resultat.put(id, new PrixEnVigueur(prix.get(TypePrix.VENTE), prix.get(TypePrix.MINIMUM),
				prix.get(TypePrix.ACHAT_REFERENCE))));
		return resultat;
	}

	@Transactional(readOnly = true)
	public PrixEnVigueur aujourdhui(Long modeleId) {
		return enVigueur(List.of(modeleId), LocalDate.now(horloge)).getOrDefault(modeleId, PrixEnVigueur.VIDE);
	}

	@Transactional(readOnly = true)
	public List<LignePrix> historique(Long modeleId) {
		return jdbc.sql("""
						SELECT p.id, p.type_prix, p.valeur, p.date_effet, p.motif, p.cree_le, p.cree_par,
						       trim(coalesce(u.prenom || ' ', '') || coalesce(u.nom, '')) AS auteur
						FROM prix_historique p LEFT JOIN utilisateurs u ON u.id = p.cree_par
						WHERE p.modele_id = :m
						ORDER BY p.date_effet DESC, p.id DESC""")
				.param("m", modeleId)
				.query((rs, n) -> new LignePrix(rs.getLong("id"), TypePrix.valueOf(rs.getString("type_prix")), rs.getLong("valeur"),
						rs.getDate("date_effet").toLocalDate(), rs.getString("motif"), rs.getTimestamp("cree_le").toInstant(),
						(Long) rs.getObject("cree_par"), rs.getString("auteur")))
				.list();
	}

	/**
	 * Vérifie que prix minimum ≤ prix de vente à toutes les dates touchées par un changement
	 * (la date d'effet et chaque date ultérieure où un prix change) ; renvoie la première date en défaut, s'il y en a une.
	 */
	@Transactional(readOnly = true)
	public Optional<LocalDate> premiereIncoherence(Long modeleId, LocalDate dateEffet, List<ChangementPrix> changements) {
		record Point(TypePrix type, long valeur, LocalDate date, long ordre) {
		}
		List<Point> points = new ArrayList<>();
		long ordre = 0;
		for (LignePrix ligne : historique(modeleId).reversed()) {
			if (ligne.type() != TypePrix.ACHAT_REFERENCE) {
				points.add(new Point(ligne.type(), ligne.valeur(), ligne.dateEffet(), ordre++));
			}
		}
		for (ChangementPrix c : changements) {
			if (c.type() != TypePrix.ACHAT_REFERENCE) {
				points.add(new Point(c.type(), c.valeur(), dateEffet, ordre++));
			}
		}
		TreeSet<LocalDate> dates = new TreeSet<>();
		dates.add(dateEffet);
		points.stream().map(Point::date).filter(d -> d.isAfter(dateEffet)).forEach(dates::add);
		for (LocalDate date : dates) {
			Long vente = valeurA(points.stream().filter(p -> p.type() == TypePrix.VENTE && !p.date().isAfter(date))
					.max(Comparator.comparing(Point::date).thenComparingLong(Point::ordre)).map(Point::valeur));
			Long minimum = valeurA(points.stream().filter(p -> p.type() == TypePrix.MINIMUM && !p.date().isAfter(date))
					.max(Comparator.comparing(Point::date).thenComparingLong(Point::ordre)).map(Point::valeur));
			if (vente != null && minimum != null && minimum > vente) {
				return Optional.of(date);
			}
		}
		return Optional.empty();
	}

	private static Long valeurA(Optional<Long> valeur) {
		return valeur.orElse(null);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void enregistrer(Long modeleId, ChangementPrix changement, LocalDate dateEffet, String motif, Long auteurId) {
		jdbc.sql("""
						INSERT INTO prix_historique (modele_id, type_prix, valeur, date_effet, motif, cree_par)
						VALUES (:m, :t, :v, :d, :motif, :a)""")
				.param("m", modeleId).param("t", changement.type().name()).param("v", changement.valeur())
				.param("d", dateEffet).param("motif", motif).param("a", auteurId).update();
	}

}
