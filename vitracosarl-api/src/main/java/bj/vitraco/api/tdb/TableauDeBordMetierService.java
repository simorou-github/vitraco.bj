package bj.vitraco.api.tdb;

import bj.vitraco.api.adm.emplacements.PerimetreService;
import bj.vitraco.api.app.StatutCommande;
import bj.vitraco.api.common.ErreurMetierException;
import bj.vitraco.api.common.security.ActeurAuthentifie;
import bj.vitraco.api.common.security.Droits;
import bj.vitraco.api.stk.StockDto;
import bj.vitraco.api.stk.StockService;
import bj.vitraco.api.tdb.SyntheseAdministration.Alerte;
import bj.vitraco.api.tdb.SyntheseAdministration.Alerte.Niveau;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Les tableaux de bord Commercial et Management, calculés sur les mêmes données que les modules (jamais saisis). */
@Service
class TableauDeBordMetierService {

	private static final int ANCIENNETE_JOURS = 90;
	private static final int BLOCAGE_ALERTE_JOURS = 30;
	private static final int RETARD_CRITIQUE_JOURS = 14;
	private static final int BROUILLON_ALERTE_JOURS = 3;
	private static final String STATUTS_PHYSIQUES = "('DISPONIBLE', 'RESERVEE', 'EN_PREPARATION', 'BLOQUEE')";

	private final JdbcClient jdbc;
	private final StockService stock;
	private final PerimetreService perimetres;

	TableauDeBordMetierService(JdbcClient jdbc, StockService stock, PerimetreService perimetres) {
		this.jdbc = jdbc;
		this.stock = stock;
		this.perimetres = perimetres;
	}

	// ---- Commercial ----------------------------------------------------------------------------------------------------

	@Transactional(readOnly = true)
	SyntheseCommerciale commerciale() {
		PerimetreService.Perimetre p = perimetre();
		StockDto.Indicateurs total = stock.synthese(null, null).total();
		List<StockDto.LigneModele> lignes = stock.parModele(null, null, null);
		Map<Long, Long> prix = new HashMap<>();
		jdbc.sql("""
						SELECT DISTINCT ON (modele_id) modele_id, valeur FROM prix_historique
						WHERE type_prix = 'VENTE' AND date_effet <= current_date
						ORDER BY modele_id, date_effet DESC, id DESC""")
				.query((rs, n) -> {
					prix.put(rs.getLong("modele_id"), rs.getLong("valeur"));
					return 0;
				}).list();

		List<SyntheseCommerciale.ModeleAVendre> modeles = lignes.stream()
				.filter(l -> l.indicateurs().physique() > 0)
				.sorted(Comparator.comparingInt((StockDto.LigneModele l) -> l.indicateurs().disponible()).reversed())
				.limit(8)
				.map(l -> new SyntheseCommerciale.ModeleAVendre(l.modeleId(), l.modele(), l.codeArticle(), l.indicateurs().disponible(),
						l.indicateurs().reserve(), prix.get(l.modeleId())))
				.toList();

		Set<Long> disponibles = new HashSet<>();
		lignes.stream().filter(l -> l.indicateurs().disponible() > 0).forEach(l -> disponibles.add(l.modeleId()));
		List<String> sansStock = jdbc.sql("SELECT id, marque || ' ' || nom || coalesce(' ' || version_modele, '') AS libelle FROM modeles WHERE actif ORDER BY marque, nom")
				.query((rs, n) -> Map.entry(rs.getLong("id"), rs.getString("libelle"))).list().stream()
				.filter(e -> !disponibles.contains(e.getKey())).map(Map.Entry::getValue).toList();
		long sansPrix = jdbc.sql("""
						SELECT count(*) FROM modeles m WHERE m.actif AND NOT EXISTS (
						    SELECT 1 FROM prix_historique x WHERE x.modele_id = m.id AND x.type_prix = 'VENTE' AND x.date_effet <= current_date)""")
				.query(Long.class).single();

		String lieu = p.global() ? "" : " AND c.emplacement_id IN (:ids)";
		Map<String, Object> params = p.global() ? Map.of() : Map.of("ids", p.emplacementIds());
		boolean voit = p.global() || !p.emplacementIds().isEmpty();
		long nbAnciennes = !voit ? 0 : jdbc.sql("SELECT count(*) FROM chassis c WHERE c.statut = 'DISPONIBLE' AND c.date_entree <= current_date - :j" + lieu)
				.params(params).param("j", ANCIENNETE_JOURS).query(Long.class).single();
		List<SyntheseCommerciale.MotoAncienne> anciennes = !voit ? List.of() : jdbc.sql("""
						SELECT c.id, c.numero, m.marque || ' ' || m.nom AS modele, e.nom AS emplacement, (current_date - c.date_entree) AS jours
						FROM chassis c JOIN modeles m ON m.id = c.modele_id JOIN emplacements e ON e.id = c.emplacement_id
						WHERE c.statut = 'DISPONIBLE' AND c.date_entree <= current_date - :j""" + lieu + " ORDER BY c.date_entree, c.id LIMIT 5")
				.params(params).param("j", ANCIENNETE_JOURS)
				.query((rs, n) -> new SyntheseCommerciale.MotoAncienne(rs.getLong("id"), rs.getString("numero"), rs.getString("modele"),
						rs.getString("emplacement"), rs.getLong("jours")))
				.list();

		List<Alerte> alertes = new ArrayList<>();
		if (sansPrix > 0) {
			alertes.add(new Alerte(Niveau.ATTENTION, sansPrix + (sansPrix > 1 ? " modèles actifs n'ont" : " modèle actif n'a") + " pas de prix de vente : ils ne peuvent pas être proposés.", "/catalogue/modeles"));
		}
		if (nbAnciennes > 0) {
			alertes.add(new Alerte(Niveau.INFO, nbAnciennes + (nbAnciennes > 1 ? " motos sont" : " moto est") + " disponible" + (nbAnciennes > 1 ? "s" : "")
					+ " depuis plus de " + ANCIENNETE_JOURS + " jours.", "/catalogue/chassis?statut=DISPONIBLE"));
		}
		return new SyntheseCommerciale(new SyntheseCommerciale.Disponibilite(total.disponible(), total.reserve(), total.bloque(), total.physique()), modeles,
				new SyntheseCommerciale.ModelesSansStock(sansStock.size(), sansStock.stream().limit(5).toList()), sansPrix,
				new SyntheseCommerciale.MotosAnciennes(nbAnciennes, ANCIENNETE_JOURS, anciennes), alertes);
	}

	// ---- Management ----------------------------------------------------------------------------------------------------

	@Transactional(readOnly = true)
	SyntheseManagement management() {
		PerimetreService.Perimetre p = perimetre();
		StockDto.Synthese s = stock.synthese(null, null);
		SyntheseManagement.Valorisation valorisation = null;
		if (Droits.aPermission("prix_achat.voir") && (p.global() || !p.emplacementIds().isEmpty())) {
			valorisation = jdbc.sql("SELECT coalesce(sum(cout_revient), 0) AS montant, count(*) FILTER (WHERE cout_revient IS NULL) AS sans FROM chassis c WHERE c.statut IN "
							+ STATUTS_PHYSIQUES + (p.global() ? "" : " AND c.emplacement_id IN (:ids)"))
					.params(p.global() ? Map.of() : Map.of("ids", p.emplacementIds()))
					.query((rs, n) -> new SyntheseManagement.Valorisation(rs.getLong("montant"), rs.getLong("sans"))).single();
		}
		var stockVue = new SyntheseManagement.Stock(s.consolide(), s.total(), s.global(), s.importation(), s.emplacements(), valorisation);

		List<SyntheseManagement.Statut> parStatut = jdbc.sql("SELECT statut, count(*) AS n FROM commandes_fournisseur WHERE statut NOT IN ('RECEPTIONNEE', 'ANNULEE') GROUP BY statut")
				.query((rs, n) -> new SyntheseManagement.Statut(rs.getString("statut"), StatutCommande.valueOf(rs.getString("statut")).libelle(), rs.getLong("n")))
				.list().stream().sorted(Comparator.comparingInt(x -> StatutCommande.valueOf(x.code()).ordinal())).toList();
		long enCours = parStatut.stream().mapToLong(SyntheseManagement.Statut::nombre).sum();
		List<SyntheseManagement.ArrivageEnRetard> retards = jdbc.sql("""
						SELECT a.id, a.numero_bl, c.numero AS commande, f.raison_sociale, (current_date - a.date_arrivee_prevue) AS retard
						FROM arrivages a JOIN commandes_fournisseur c ON c.id = a.commande_id JOIN fournisseurs f ON f.id = c.fournisseur_id
						WHERE a.date_arrivee_reelle IS NULL AND a.date_arrivee_prevue < current_date AND c.statut NOT IN ('ANNULEE', 'RECEPTIONNEE')
						ORDER BY a.date_arrivee_prevue, a.id""")
				.query((rs, n) -> new SyntheseManagement.ArrivageEnRetard(rs.getLong("id"), rs.getString("numero_bl"), rs.getString("commande"),
						rs.getString("raison_sociale"), rs.getLong("retard")))
				.list();
		long aReceptionner = jdbc.sql("""
						SELECT count(*) FROM arrivages a JOIN commandes_fournisseur c ON c.id = a.commande_id
						WHERE a.statut_douane = 'TERMINEE' AND c.statut IN ('A_RECEPTIONNER', 'RECEPTION_PARTIELLE')""")
				.query(Long.class).single();
		var appro = new SyntheseManagement.Approvisionnements(enCours, parStatut, retards.size(), retards.stream().limit(5).toList(), aReceptionner);

		var receptions = jdbc.sql("""
						SELECT count(*) FILTER (WHERE r.statut = 'BROUILLON') AS brouillons,
						       count(*) FILTER (WHERE r.statut = 'VALIDEE' AND r.valide_le >= now() - interval '30 days') AS validees,
						       coalesce(sum((SELECT count(*) FROM lignes_reception l WHERE l.reception_id = r.id)) FILTER (WHERE r.statut = 'VALIDEE' AND r.valide_le >= now() - interval '30 days'), 0) AS motos
						FROM receptions r""")
				.query((rs, n) -> new SyntheseManagement.Receptions(rs.getLong("brouillons"), rs.getLong("validees"), rs.getLong("motos"))).single();
		long brouillonsAnciens = jdbc.sql("SELECT count(*) FROM receptions WHERE statut = 'BROUILLON' AND cree_le < now() - make_interval(days => :j)")
				.param("j", BROUILLON_ALERTE_JOURS).query(Long.class).single();

		long[] bloques = jdbc.sql("""
						SELECT count(*) AS n, coalesce(max(current_date - dernier.cree_le::date), 0) AS jours
						FROM chassis c LEFT JOIN LATERAL (SELECT x.cree_le FROM mouvements_stock x WHERE x.chassis_id = c.id AND x.statut_apres = 'BLOQUEE'
						                                  ORDER BY x.cree_le DESC, x.id DESC LIMIT 1) dernier ON true
						WHERE c.statut = 'BLOQUEE'""" + (p.global() ? "" : " AND c.emplacement_id IN (:ids)"))
				.params(p.global() ? Map.of() : Map.of("ids", p.emplacementIds().isEmpty() ? Set.of(-1L) : p.emplacementIds()))
				.query((rs, n) -> new long[]{rs.getLong("n"), rs.getLong("jours")}).single();
		long corrections = jdbc.sql("SELECT count(*) FROM corrections_numero WHERE statut = 'DEMANDEE'").query(Long.class).single();
		long[] transferts = jdbc.sql("SELECT count(*) AS n, count(*) FILTER (WHERE alerte_retard_le IS NOT NULL) AS retard FROM transferts WHERE statut = 'EXPEDIE'")
				.query((rs, n) -> new long[]{rs.getLong("n"), rs.getLong("retard")}).single();
		var controle = new SyntheseManagement.Controle(bloques[0], bloques[1], corrections, transferts[0], transferts[1]);

		List<Alerte> alertes = new ArrayList<>();
		if (!retards.isEmpty()) {
			long max = retards.get(0).retardJours();
			alertes.add(new Alerte(max > RETARD_CRITIQUE_JOURS ? Niveau.CRITIQUE : Niveau.ATTENTION,
					retards.size() + (retards.size() > 1 ? " arrivages sont en retard" : " arrivage est en retard") + " (jusqu'à " + max + " jours).", "/approvisionnements/arrivages"));
		}
		if (bloques[0] > 0 && bloques[1] > BLOCAGE_ALERTE_JOURS) {
			alertes.add(new Alerte(Niveau.ATTENTION, "Des motos sont bloquées depuis plus de " + BLOCAGE_ALERTE_JOURS + " jours (la plus ancienne : " + bloques[1] + " jours).", "/stock/bloques"));
		}
		if (corrections > 0) {
			alertes.add(new Alerte(Niveau.INFO, corrections + (corrections > 1 ? " corrections de numéro de châssis attendent" : " correction de numéro de châssis attend") + " une validation.", "/catalogue/chassis"));
		}
		if (transferts[1] > 0) {
			alertes.add(new Alerte(Niveau.ATTENTION, transferts[1] + (transferts[1] > 1 ? " transferts ne sont pas réceptionnés" : " transfert n'est pas réceptionné") + " dans le délai.", "/stock/transferts"));
		}
		if (brouillonsAnciens > 0) {
			alertes.add(new Alerte(Niveau.ATTENTION, brouillonsAnciens + (brouillonsAnciens > 1 ? " réceptions sont ouvertes" : " réception est ouverte") + " depuis plus de " + BROUILLON_ALERTE_JOURS + " jours sans validation.", "/stock/receptions"));
		}
		return new SyntheseManagement(stockVue, appro, new SyntheseManagement.Receptions(receptions.brouillons(), receptions.valideesSur30Jours(), receptions.motosRecuesSur30Jours()), controle, alertes);
	}

	private PerimetreService.Perimetre perimetre() {
		Long utilisateur = ActeurAuthentifie.courant().map(ActeurAuthentifie::id)
				.orElseThrow(() -> new ErreurMetierException("NON_AUTHENTIFIE", "Connexion requise.", HttpStatus.UNAUTHORIZED));
		return perimetres.perimetreDe(utilisateur);
	}

}
