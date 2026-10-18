package bj.vitraco.api.trf;

import bj.vitraco.api.adm.emplacements.PerimetreService;
import bj.vitraco.api.adm.numerotation.NumerotationService;
import bj.vitraco.api.adm.numerotation.TypeDocument;
import bj.vitraco.api.adm.parametres.CatalogueParametres;
import bj.vitraco.api.adm.parametres.ParametreService;
import bj.vitraco.api.ano.AnomalieService;
import bj.vitraco.api.aud.EntreeAudit;
import bj.vitraco.api.aud.JournalAudit;
import bj.vitraco.api.cat.chassis.ChassisService;
import bj.vitraco.api.cat.chassis.NumeroChassis;
import bj.vitraco.api.cat.chassis.StatutChassis;
import bj.vitraco.api.common.CodeModule;
import bj.vitraco.api.common.ErreurMetierException;
import bj.vitraco.api.common.ErreurValidationException;
import bj.vitraco.api.common.security.ActeurAuthentifie;
import bj.vitraco.api.common.security.Droits;
import bj.vitraco.api.notif.DemandeNotification;
import bj.vitraco.api.notif.Notifications;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Transferts internes à trois temps (SRS 5.7). La création ne bouge rien ; l'expédition fait sortir les châssis de la source
 * et les place en transit interne ; la réception, par pointage, les fait entrer à la destination. Un châssis manquant reste
 * en transit et ouvre l'anomalie A12 ; un châssis non prévu est refusé et signalé.
 */
@Service
public class TransfertService {

	private static final Logger log = LoggerFactory.getLogger(TransfertService.class);
	private static final String LIBELLE_STATUT_INITIE = "Initié";

	public record Filtre(String statut, Long sourceId, Long destinationId, String vue, String recherche) {
	}

	private record Entete(Long id, String numero, Long sourceId, Long destinationId, String statut, String observation, Long retourDeId, Long creePar,
						  Instant creeLe, Long expediePar, Instant expedieLe, Instant receptionneLe, Long annulePar, Instant annuleLe,
						  String motifAnnulation, Instant ecartSignaleLe, Instant alerteRetardLe) {
	}

	private final JdbcClient jdbc;
	private final ChassisService chassisService;
	private final NumerotationService numerotation;
	private final PerimetreService perimetres;
	private final ParametreService parametres;
	private final AnomalieService anomalies;
	private final JournalAudit journal;
	private final Notifications notifications;
	private final TransactionTemplate transaction;

	TransfertService(JdbcClient jdbc, ChassisService chassisService, NumerotationService numerotation, PerimetreService perimetres,
					 ParametreService parametres, AnomalieService anomalies, JournalAudit journal, Notifications notifications,
					 TransactionTemplate transaction) {
		this.jdbc = jdbc;
		this.chassisService = chassisService;
		this.numerotation = numerotation;
		this.perimetres = perimetres;
		this.parametres = parametres;
		this.anomalies = anomalies;
		this.journal = journal;
		this.notifications = notifications;
		this.transaction = transaction;
	}

	// ---- Lecture -------------------------------------------------------------------------------------------------------

	/** Visible depuis la source comme depuis la destination (EF-TRF-09). */
	@Transactional(readOnly = true)
	public Page<TransfertDto.Liste> lister(Filtre filtre, Pageable pagination) {
		PerimetreService.Perimetre p = perimetre();
		List<String> criteres = new ArrayList<>();
		Map<String, Object> params = new HashMap<>();
		if (!p.global()) {
			if (p.emplacementIds().isEmpty()) {
				return new PageImpl<>(List.of(), pagination, 0);
			}
			criteres.add("(t.source_id IN (:ids) OR t.destination_id IN (:ids))");
			params.put("ids", p.emplacementIds());
		}
		if (filtre.statut() != null && !filtre.statut().isBlank()) {
			criteres.add("t.statut = :statut");
			params.put("statut", filtre.statut().toUpperCase(Locale.ROOT));
		}
		if (filtre.sourceId() != null) {
			criteres.add("t.source_id = :src");
			params.put("src", filtre.sourceId());
		}
		if (filtre.destinationId() != null) {
			criteres.add("t.destination_id = :dst");
			params.put("dst", filtre.destinationId());
		}
		// « À expédier » chez la source, « à réceptionner » chez la destination.
		if ("a_expedier".equals(filtre.vue())) {
			criteres.add("t.statut = 'INITIE'");
			if (!p.global()) {
				criteres.add("t.source_id IN (:ids)");
			}
		} else if ("a_receptionner".equals(filtre.vue())) {
			criteres.add("t.statut = 'EXPEDIE'");
			if (!p.global()) {
				criteres.add("t.destination_id IN (:ids)");
			}
		}
		if (filtre.recherche() != null && !filtre.recherche().isBlank()) {
			criteres.add("(lower(t.numero) LIKE :q OR EXISTS (SELECT 1 FROM lignes_transfert l JOIN chassis c ON c.id = l.chassis_id WHERE l.transfert_id = t.id AND c.numero LIKE :n))");
			params.put("q", "%" + filtre.recherche().trim().toLowerCase(Locale.ROOT) + "%");
			params.put("n", "%" + NumeroChassis.normaliser(filtre.recherche()) + "%");
		}
		String ou = criteres.isEmpty() ? "" : " WHERE " + String.join(" AND ", criteres);
		String base = " FROM transferts t JOIN emplacements s ON s.id = t.source_id JOIN emplacements d ON d.id = t.destination_id" + ou;
		long total = jdbc.sql("SELECT count(*)" + base).params(params).query(Long.class).single();
		Optional<Long> delai = delaiReception();
		var contenu = jdbc.sql("SELECT t.id, t.numero, t.statut, s.nom AS source, d.nom AS destination, t.cree_le, t.expedie_le,"
						+ " (SELECT count(*) FROM lignes_transfert l WHERE l.transfert_id = t.id) AS nb,"
						+ " (SELECT count(*) FROM lignes_transfert l WHERE l.transfert_id = t.id AND l.recu_le IS NOT NULL) AS recus" + base
						+ " ORDER BY t.cree_le DESC, t.id DESC LIMIT :limite OFFSET :decalage")
				.params(params).param("limite", pagination.getPageSize()).param("decalage", pagination.getOffset())
				.query((rs, n) -> {
					Instant expedie = instant(rs, "expedie_le");
					String statut = rs.getString("statut");
					return new TransfertDto.Liste(rs.getLong("id"), rs.getString("numero"), statut, libelleStatut(statut), rs.getString("source"),
							rs.getString("destination"), rs.getInt("nb"), rs.getInt("recus"), instant(rs, "cree_le"), expedie,
							enRetard(statut, expedie, delai));
				}).list();
		return new PageImpl<>(contenu, pagination, total);
	}

	@Transactional(readOnly = true)
	public TransfertDto detail(Long id) {
		Entete e = charger(id);
		verifierVisible(e);
		return construire(e);
	}

	/** Les châssis disponibles à la source et libres de tout autre transfert en cours : de quoi composer la liste. */
	@Transactional(readOnly = true)
	public List<TransfertDto.Transferable> transferables(Long sourceId, String recherche, Long modeleId) {
		PerimetreService.Perimetre p = perimetre();
		// Le demandeur d'une boutique voit le stock du magasin dont il a besoin pour composer sa demande.
		if (!p.global() && p.emplacementIds().isEmpty()) {
			throw introuvable();
		}
		StringBuilder sql = new StringBuilder("""
				SELECT c.id, c.numero, m.marque || ' ' || m.nom AS modele, m.code_article, (current_date - c.date_entree) AS jours
				FROM chassis c JOIN modeles m ON m.id = c.modele_id
				WHERE c.emplacement_id = :s AND c.statut = 'DISPONIBLE'
				  AND NOT EXISTS (SELECT 1 FROM lignes_transfert l JOIN transferts t ON t.id = l.transfert_id
				                  WHERE l.chassis_id = c.id AND t.statut IN ('INITIE', 'EXPEDIE'))""");
		Map<String, Object> params = new HashMap<>();
		params.put("s", sourceId);
		if (modeleId != null) {
			sql.append(" AND c.modele_id = :m");
			params.put("m", modeleId);
		}
		if (recherche != null && !recherche.isBlank()) {
			sql.append(" AND c.numero LIKE :q");
			params.put("q", "%" + NumeroChassis.normaliser(recherche) + "%");
		}
		sql.append(" ORDER BY c.date_entree, c.numero LIMIT 300");
		return jdbc.sql(sql.toString()).params(params)
				.query((rs, n) -> new TransfertDto.Transferable(rs.getLong("id"), rs.getString("numero"), rs.getString("modele"), rs.getString("code_article"),
						rs.getObject("jours") == null ? null : rs.getLong("jours")))
				.list();
	}

	// ---- Création et modification --------------------------------------------------------------------------------------

	@Transactional
	public TransfertDto creer(TransfertRequete requete) {
		Lieux lieux = verifierLieux(requete);
		List<Long> ids = resoudreChassis(requete.numeros(), lieux.source(), null);
		if (requete.retourDeId() != null) {
			Entete origine = charger(requete.retourDeId());
			if (!"EXPEDIE".equals(origine.statut()) && !"RECEPTIONNE".equals(origine.statut())) {
				throw new ErreurValidationException("retour_de_id", "Seul un transfert expédié peut être corrigé par un transfert retour.");
			}
		}
		String codeSource = jdbc.sql("SELECT code FROM emplacements WHERE id = :i").param("i", lieux.source()).query(String.class).single();
		String numero = numerotation.prochainNumero(TypeDocument.TRANSFERT, codeSource);
		Long id = jdbc.sql("""
						INSERT INTO transferts (numero, source_id, destination_id, observation, retour_de_id, cree_par)
						VALUES (:n, :s, :d, :o, :r, :u) RETURNING id""")
				.param("n", numero).param("s", lieux.source()).param("d", lieux.destination()).param("o", vide(requete.observation()))
				.param("r", requete.retourDeId()).param("u", acteurId()).query(Long.class).single();
		ajouterLignes(id, ids);
		journal.enregistrer(EntreeAudit.de(CodeModule.TRF, "transfert.cree", "Transfert", String.valueOf(id))
				.avecValeurs(null, Map.of("numero", numero, "source", lieux.source(), "destination", lieux.destination(), "chassis", ids.size())));
		return construire(charger(id));
	}

	/** Un transfert « initié » se corrige librement : lieux, observation et liste de châssis. Aucun mouvement n'a eu lieu. */
	@Transactional
	public TransfertDto modifier(Long id, TransfertRequete requete) {
		Entete e = verrouiller(id);
		verifierVisible(e);
		exigerStatut(e, "INITIE", "Seul un transfert initié peut être modifié.");
		Lieux lieux = verifierLieux(requete);
		List<Long> ids = resoudreChassis(requete.numeros(), lieux.source(), id);
		jdbc.sql("UPDATE transferts SET source_id = :s, destination_id = :d, observation = :o WHERE id = :i")
				.param("s", lieux.source()).param("d", lieux.destination()).param("o", vide(requete.observation())).param("i", id).update();
		jdbc.sql("DELETE FROM lignes_transfert WHERE transfert_id = :i").param("i", id).update();
		ajouterLignes(id, ids);
		journal.enregistrer(EntreeAudit.de(CodeModule.TRF, "transfert.modifie", "Transfert", String.valueOf(id))
				.avecValeurs(Map.of("source", e.sourceId(), "destination", e.destinationId()), Map.of("source", lieux.source(), "destination", lieux.destination(), "chassis", ids.size())));
		return construire(charger(id));
	}

	// ---- Les trois temps -----------------------------------------------------------------------------------------------

	/** Expédition (EF-TRF-02) : les châssis sortent de la source et passent « en transit interne ». */
	@Transactional
	public TransfertDto expedier(Long id) {
		Entete e = verrouiller(id);
		verifierVisible(e);
		exigerStatut(e, "INITIE", "Ce transfert n'est pas à expédier.");
		PerimetreService.Perimetre p = perimetre();
		if (!p.global() && !p.autorise(e.sourceId())) {
			throw new ErreurMetierException("HORS_PERIMETRE", "Un transfert s'expédie depuis son emplacement source.", HttpStatus.FORBIDDEN);
		}
		Long transit = jdbc.sql("SELECT id FROM emplacements WHERE type = 'TRANSIT' AND actif").query(Long.class).optional()
				.orElseThrow(() -> new ErreurMetierException("TRANSIT_ABSENT", "L'emplacement de transit interne est introuvable."));
		List<Long> chassisIds = jdbc.sql("SELECT chassis_id FROM lignes_transfert WHERE transfert_id = :i ORDER BY id").param("i", id).query(Long.class).list();
		if (chassisIds.isEmpty()) {
			throw new ErreurMetierException("TRANSFERT_VIDE", "Un transfert expédie au moins un châssis.");
		}
		for (Long chassisId : chassisIds) {
			chassisService.expedier(chassisId, e.sourceId(), transit, id);
		}
		jdbc.sql("UPDATE transferts SET statut = 'EXPEDIE', expedie_par = :u, expedie_le = now() WHERE id = :i").param("u", acteurId()).param("i", id).update();
		journal.enregistrer(EntreeAudit.de(CodeModule.TRF, "transfert.expedie", "Transfert", String.valueOf(id))
				.avecValeurs(Map.of("statut", "INITIE"), Map.of("statut", "EXPEDIE", "chassis", chassisIds.size())));
		String auteur = ActeurAuthentifie.courant().map(ActeurAuthentifie::libelle).orElse("Le système");
		for (Long utilisateur : personnelDe(e.destinationId())) {
			notifications.notifierUtilisateur(utilisateur, DemandeNotification.de("transfert.a_receptionner", "Transfert à réceptionner : " + e.numero(),
					auteur + " a expédié " + chassisIds.size() + " moto(s) vers votre emplacement. Pointez-les à l'arrivée.", "/stock/transferts/" + id));
		}
		return construire(charger(id));
	}

	/** Annulation : possible avant l'expédition seulement. Ensuite, seul un transfert retour corrige (EF-TRF-07). */
	@Transactional
	public TransfertDto annuler(Long id, AnnulationTransfertRequete requete) {
		Entete e = verrouiller(id);
		verifierVisible(e);
		if (!"INITIE".equals(e.statut())) {
			throw new ErreurMetierException("TRANSFERT_NON_ANNULABLE", "EXPEDIE".equals(e.statut()) || "RECEPTIONNE".equals(e.statut())
					? "Ce transfert est déjà parti : seul un transfert retour peut le corriger."
					: "Ce transfert n'est pas annulable.");
		}
		jdbc.sql("UPDATE transferts SET statut = 'ANNULE', annule_par = :u, annule_le = now(), motif_annulation = :m WHERE id = :i")
				.param("u", acteurId()).param("m", requete.motif().trim()).param("i", id).update();
		journal.enregistrer(EntreeAudit.de(CodeModule.TRF, "transfert.annule", "Transfert", String.valueOf(id))
				.avecValeurs(Map.of("statut", "INITIE"), Map.of("statut", "ANNULE")).avecMotif(requete.motif().trim()));
		return construire(charger(id));
	}

	/**
	 * Réception par pointage (EF-TRF-04, EF-TRF-10). Les châssis du transfert entrent à la destination ; un numéro non
	 * prévu, déjà reçu ou inconnu est refusé et signalé. Le reste du transfert peut arriver plus tard, ou être clôturé avec
	 * un écart qui ouvre l'anomalie A12.
	 */
	@Transactional
	public TransfertDto.ResultatReception receptionner(Long id, ReceptionTransfertRequete requete) {
		Entete e = verrouiller(id);
		verifierVisible(e);
		exigerStatut(e, "EXPEDIE", "Ce transfert n'est pas à réceptionner.");
		PerimetreService.Perimetre p = perimetre();
		if (!p.global() && !p.autorise(e.destinationId())) {
			throw new ErreurMetierException("HORS_PERIMETRE", "Un transfert se réceptionne à sa destination.", HttpStatus.FORBIDDEN);
		}
		Long acteur = acteurId();
		if (parametres.booleen(CatalogueParametres.TRANSFERT_UTILISATEURS_DIFFERENTS).orElse(true) && acteur.equals(e.expediePar())) {
			throw new ErreurMetierException("UTILISATEUR_IDENTIQUE", "Un transfert doit être réceptionné par un autre utilisateur que celui qui l'a expédié.", HttpStatus.FORBIDDEN);
		}

		Map<String, Long[]> attendus = new LinkedHashMap<>(); // numéro → {ligne, chassis, statut_origine rang}
		Map<String, String> statutsOrigine = new HashMap<>();
		jdbc.sql("""
						SELECT l.id, l.chassis_id, c.numero, l.statut_origine, l.recu_le FROM lignes_transfert l JOIN chassis c ON c.id = l.chassis_id
						WHERE l.transfert_id = :i ORDER BY l.id""").param("i", id)
				.query((rs, n) -> {
					if (rs.getTimestamp("recu_le") == null) {
						attendus.put(rs.getString("numero"), new Long[]{rs.getLong("id"), rs.getLong("chassis_id")});
						statutsOrigine.put(rs.getString("numero"), rs.getString("statut_origine"));
					}
					return 0;
				}).list();
		Set<String> dejaRecus = new LinkedHashSet<>(jdbc.sql("SELECT c.numero FROM lignes_transfert l JOIN chassis c ON c.id = l.chassis_id WHERE l.transfert_id = :i AND l.recu_le IS NOT NULL")
				.param("i", id).query(String.class).list());

		List<TransfertDto.Refus> refus = new ArrayList<>();
		Set<String> vus = new LinkedHashSet<>();
		int recus = 0;
		for (String saisi : requete.numeros()) {
			String numero = NumeroChassis.normaliser(saisi);
			if (numero.isBlank() || !vus.add(numero)) {
				continue;
			}
			Long[] ligne = attendus.get(numero);
			if (ligne != null) {
				chassisService.receptionnerTransfert(ligne[1], e.destinationId(), StatutChassis.valueOf(statutsOrigine.get(numero)), id);
				jdbc.sql("UPDATE lignes_transfert SET recu_le = now(), recu_par = :u WHERE id = :l").param("u", acteur).param("l", ligne[0]).update();
				attendus.remove(numero);
				recus++;
			} else if (dejaRecus.contains(numero)) {
				refus.add(new TransfertDto.Refus(numero, "Déjà réceptionné dans ce transfert."));
			} else if (jdbc.sql("SELECT count(*) FROM chassis WHERE numero = :n").param("n", numero).query(Long.class).single() > 0) {
				refus.add(new TransfertDto.Refus(numero, "Non prévu : ce châssis ne fait pas partie de ce transfert."));
			} else {
				refus.add(new TransfertDto.Refus(numero, "Châssis inconnu."));
			}
		}

		boolean complet = attendus.isEmpty();
		if (complet) {
			jdbc.sql("UPDATE transferts SET statut = 'RECEPTIONNE', receptionne_le = now() WHERE id = :i").param("i", id).update();
		}
		journal.enregistrer(EntreeAudit.de(CodeModule.TRF, complet ? "transfert.receptionne" : "transfert.reception_partielle", "Transfert", String.valueOf(id))
				.avecValeurs(Map.of("statut", "EXPEDIE"), Map.of("statut", complet ? "RECEPTIONNE" : "EXPEDIE", "recus", recus, "manquants", attendus.size())));

		String auteur = ActeurAuthentifie.courant().map(ActeurAuthentifie::libelle).orElse("Le système");
		if (!refus.isEmpty()) {
			journal.enregistrer(EntreeAudit.de(CodeModule.TRF, "transfert.scan_refuse", "Transfert", String.valueOf(id))
					.avecValeurs(null, Map.of("numeros", refus.stream().map(TransfertDto.Refus::numero).toList())));
			notifications.notifierRole("controleur", DemandeNotification.de("transfert.scan_refuse", "Châssis refusé à la réception : " + e.numero(),
					auteur + " a pointé " + refus.size() + " numéro(s) qui ne correspondent pas au transfert : "
							+ String.join(", ", refus.stream().map(TransfertDto.Refus::numero).toList()) + ".", "/stock/transferts/" + id));
		}
		if (complet) {
			notifications.notifierUtilisateur(e.expediePar(), DemandeNotification.de("transfert.receptionne", "Transfert réceptionné : " + e.numero(),
					auteur + " a réceptionné le transfert en entier.", "/stock/transferts/" + id));
		} else if (Boolean.TRUE.equals(requete.cloturerAvecEcart())) {
			signalerEcart(e, attendus.keySet(), "Réception clôturée avec un écart");
		}
		return new TransfertDto.ResultatReception(construire(charger(id)), recus, refus);
	}

	// ---- Contrôle des retards (EF-TRF-08) ------------------------------------------------------------------------------

	/** Chaque matin : tout transfert expédié depuis plus que le délai paramétré alerte le contrôle et ouvre l'anomalie A12. */
	@Scheduled(cron = "${vitraco.transferts.controle-retards-cron:0 0 7 * * *}", zone = "Africa/Porto-Novo")
	void controleQuotidien() {
		try {
			int alertes = controlerRetards();
			if (alertes > 0) {
				log.info("{} transfert(s) non réceptionné(s) dans le délai : alerte et anomalie A12 ouvertes", alertes);
			}
		} catch (RuntimeException e) {
			log.error("Contrôle des transferts en retard impossible", e);
		}
	}

	/** @return le nombre de transferts nouvellement signalés. Sans délai paramétré, aucun contrôle n'est appliqué. */
	public int controlerRetards() {
		Optional<Long> delai = delaiReception();
		if (delai.isEmpty()) {
			return 0;
		}
		List<Long> ids = jdbc.sql("SELECT id FROM transferts WHERE statut = 'EXPEDIE' AND alerte_retard_le IS NULL AND expedie_le < now() - make_interval(days => :j)")
				.param("j", delai.get().intValue()).query(Long.class).list();
		for (Long id : ids) {
			transaction.executeWithoutResult(s -> {
				Entete e = charger(id);
				Set<String> manquants = new LinkedHashSet<>(jdbc.sql("SELECT c.numero FROM lignes_transfert l JOIN chassis c ON c.id = l.chassis_id WHERE l.transfert_id = :i AND l.recu_le IS NULL ORDER BY l.id")
						.param("i", id).query(String.class).list());
				jdbc.sql("UPDATE transferts SET alerte_retard_le = now() WHERE id = :i").param("i", id).update();
				signalerEcart(e, manquants, "Transfert non réceptionné après " + delai.get() + " jour(s)");
			});
		}
		return ids.size();
	}

	/** Ouvre (ou met à jour) l'anomalie A12 d'un transfert et prévient le contrôle. */
	private void signalerEcart(Entete e, Set<String> manquants, String raison) {
		if (manquants.isEmpty()) {
			return;
		}
		Long cout = jdbc.sql("""
						SELECT coalesce(sum(c.cout_revient), 0) FROM lignes_transfert l JOIN chassis c ON c.id = l.chassis_id
						WHERE l.transfert_id = :i AND l.recu_le IS NULL""").param("i", e.id()).query(Long.class).single();
		String numero = anomalies.ouvrir("A12", "Transfert", e.id(), "Transfert non réceptionné : " + e.numero(),
				raison + ". Châssis manquants (" + manquants.size() + ") : " + String.join(", ", manquants) + ".", cout);
		jdbc.sql("UPDATE transferts SET ecart_signale_le = coalesce(ecart_signale_le, now()) WHERE id = :i").param("i", e.id()).update();
		notifications.notifierRole("controleur", DemandeNotification.de("transfert.ecart", "Transfert non réceptionné : " + e.numero(),
				raison + " : " + manquants.size() + " moto(s) restent en transit interne. Anomalie " + numero + " ouverte.", "/stock/transferts/" + e.id()));
	}

	// ---- Bon de transfert ----------------------------------------------------------------------------------------------

	@Transactional(readOnly = true)
	public byte[] bon(Long id) {
		TransfertDto t = detail(id);
		if ("INITIE".equals(t.statut()) || "ANNULE".equals(t.statut())) {
			throw new ErreurMetierException("BON_APRES_EXPEDITION", "Le bon de transfert accompagne les motos : il s'édite après l'expédition.");
		}
		return BonTransfert.generer(t);
	}

	// ---- Outils --------------------------------------------------------------------------------------------------------

	private record Lieux(Long source, Long destination) {
	}

	/** Source et destination sont des magasins ou boutiques actifs ; le demandeur est de l'un ou de l'autre (EF-TRF-01). */
	private Lieux verifierLieux(TransfertRequete requete) {
		if (requete.sourceId().equals(requete.destinationId())) {
			throw new ErreurValidationException("destination_id", "La destination doit différer de la source.");
		}
		for (var champ : List.of(Map.entry("source_id", requete.sourceId()), Map.entry("destination_id", requete.destinationId()))) {
			boolean valide = jdbc.sql("SELECT count(*) FROM emplacements WHERE id = :i AND actif AND type IN ('MAGASIN', 'BOUTIQUE')")
					.param("i", champ.getValue()).query(Long.class).single() > 0;
			if (!valide) {
				throw new ErreurValidationException(champ.getKey(), "Un transfert relie deux magasins ou boutiques actifs.");
			}
		}
		PerimetreService.Perimetre p = perimetre();
		if (!p.global() && !p.autorise(requete.sourceId()) && !p.autorise(requete.destinationId())) {
			throw new ErreurMetierException("HORS_PERIMETRE", "Le transfert doit partir de l'un de vos emplacements ou y arriver.", HttpStatus.FORBIDDEN);
		}
		return new Lieux(requete.sourceId(), requete.destinationId());
	}

	/** Chaque numéro doit être un châssis disponible à la source, hors de tout autre transfert en cours (EF-TRF-03). */
	private List<Long> resoudreChassis(List<String> numeros, Long source, Long transfertCourant) {
		if (numeros == null || numeros.stream().noneMatch(n -> n != null && !n.isBlank())) {
			throw new ErreurValidationException("numeros", "Un transfert comporte au moins un châssis.");
		}
		Set<String> uniques = new LinkedHashSet<>();
		numeros.stream().filter(n -> n != null && !n.isBlank()).map(NumeroChassis::normaliser).forEach(uniques::add);
		List<Long> ids = new ArrayList<>();
		List<String> erreurs = new ArrayList<>();
		for (String numero : uniques) {
			var c = jdbc.sql("SELECT id, statut, emplacement_id FROM chassis WHERE numero = :n FOR UPDATE").param("n", numero)
					.query((rs, i) -> new Object[]{rs.getLong("id"), rs.getString("statut"), rs.getObject("emplacement_id")}).optional();
			if (c.isEmpty()) {
				erreurs.add(numero + " : châssis inconnu");
			} else if (!"DISPONIBLE".equals(c.get()[1]) || !source.equals(c.get()[2])) {
				erreurs.add(numero + " : n'est pas un châssis disponible à la source");
			} else if (jdbc.sql("""
							SELECT count(*) FROM lignes_transfert l JOIN transferts t ON t.id = l.transfert_id
							WHERE l.chassis_id = :c AND t.statut IN ('INITIE', 'EXPEDIE') AND t.id <> :cur""")
					.param("c", c.get()[0]).param("cur", transfertCourant == null ? -1L : transfertCourant).query(Long.class).single() > 0) {
				erreurs.add(numero + " : déjà dans un autre transfert en cours");
			} else {
				ids.add((Long) c.get()[0]);
			}
		}
		if (!erreurs.isEmpty()) {
			throw new ErreurValidationException("numeros", String.join(" ; ", erreurs) + ".");
		}
		return ids;
	}

	private void ajouterLignes(Long transfertId, List<Long> chassisIds) {
		for (Long chassisId : chassisIds) {
			jdbc.sql("INSERT INTO lignes_transfert (transfert_id, chassis_id, statut_origine) VALUES (:t, :c, 'DISPONIBLE')")
					.param("t", transfertId).param("c", chassisId).update();
		}
	}

	private TransfertDto construire(Entete e) {
		var source = emplacement(e.sourceId());
		var destination = emplacement(e.destinationId());
		List<TransfertDto.Ligne> lignes = jdbc.sql("""
						SELECT l.id, l.chassis_id, c.numero, m.marque || ' ' || m.nom AS modele, m.code_article, l.recu_le, u.nom || coalesce(' ' || u.prenom, '') AS recu_par
						FROM lignes_transfert l JOIN chassis c ON c.id = l.chassis_id JOIN modeles m ON m.id = c.modele_id
						LEFT JOIN utilisateurs u ON u.id = l.recu_par WHERE l.transfert_id = :i ORDER BY l.id""")
				.param("i", e.id())
				.query((rs, n) -> new TransfertDto.Ligne(rs.getLong("id"), rs.getLong("chassis_id"), rs.getString("numero"), rs.getString("modele"), rs.getString("code_article"),
						rs.getTimestamp("recu_le") != null, instant(rs, "recu_le"), rs.getString("recu_par")))
				.list();
		int recus = (int) lignes.stream().filter(TransfertDto.Ligne::recu).count();
		List<String> manquants = "EXPEDIE".equals(e.statut()) ? lignes.stream().filter(l -> !l.recu()).map(TransfertDto.Ligne::numero).toList() : List.of();
		String retourDe = e.retourDeId() == null ? null : jdbc.sql("SELECT numero FROM transferts WHERE id = :i").param("i", e.retourDeId()).query(String.class).single();
		String anomalie = jdbc.sql("SELECT numero FROM anomalies WHERE code = 'A12' AND objet_type = 'Transfert' AND objet_id = :i ORDER BY id DESC LIMIT 1")
				.param("i", e.id()).query(String.class).optional().orElse(null);
		Optional<Long> delai = delaiReception();
		boolean enRetard = enRetard(e.statut(), e.expedieLe(), delai);
		long jours = e.expedieLe() == null || "RECEPTIONNE".equals(e.statut()) ? 0 : ChronoUnit.DAYS.between(e.expedieLe(), Instant.now());
		return new TransfertDto(e.id(), e.numero(), e.statut(), libelleStatut(e.statut()), source, destination, e.observation(), retourDe, lignes, lignes.size(), recus,
				manquants, utilisateur(e.creePar()), e.creeLe(), utilisateur(e.expediePar()), e.expedieLe(), e.receptionneLe(), utilisateur(e.annulePar()), e.annuleLe(),
				e.motifAnnulation(), e.ecartSignaleLe(), enRetard, jours, anomalie, droits(e));
	}

	private TransfertDto.Droits droits(Entete e) {
		PerimetreService.Perimetre p = perimetre();
		boolean sourceOk = p.global() || p.autorise(e.sourceId());
		boolean destOk = p.global() || p.autorise(e.destinationId());
		boolean initie = "INITIE".equals(e.statut());
		boolean expedie = "EXPEDIE".equals(e.statut());
		boolean separation = parametres.booleen(CatalogueParametres.TRANSFERT_UTILISATEURS_DIFFERENTS).orElse(true) && acteurId().equals(e.expediePar());
		return new TransfertDto.Droits(
				initie && Droits.aPermission("transferts.creer") && (sourceOk || destOk),
				initie && Droits.aPermission("transferts.valider") && sourceOk,
				expedie && Droits.aPermission("transferts.modifier") && destOk && !separation,
				initie && Droits.aPermission("transferts.annuler") && (sourceOk || destOk),
				expedie || "RECEPTIONNE".equals(e.statut()));
	}

	private Entete charger(Long id) {
		return jdbc.sql("SELECT * FROM transferts WHERE id = :i").param("i", id).query((rs, n) -> versEntete(rs)).optional().orElseThrow(TransfertService::introuvable);
	}

	private Entete verrouiller(Long id) {
		return jdbc.sql("SELECT * FROM transferts WHERE id = :i FOR UPDATE").param("i", id).query((rs, n) -> versEntete(rs)).optional().orElseThrow(TransfertService::introuvable);
	}

	private static Entete versEntete(ResultSet rs) throws SQLException {
		return new Entete(rs.getLong("id"), rs.getString("numero"), rs.getLong("source_id"), rs.getLong("destination_id"), rs.getString("statut"), rs.getString("observation"),
				(Long) rs.getObject("retour_de_id"), rs.getLong("cree_par"), instant(rs, "cree_le"), (Long) rs.getObject("expedie_par"), instant(rs, "expedie_le"),
				instant(rs, "receptionne_le"), (Long) rs.getObject("annule_par"), instant(rs, "annule_le"), rs.getString("motif_annulation"), instant(rs, "ecart_signale_le"),
				instant(rs, "alerte_retard_le"));
	}

	private static Instant instant(ResultSet rs, String colonne) throws SQLException {
		return rs.getTimestamp(colonne) == null ? null : rs.getTimestamp(colonne).toInstant();
	}

	private TransfertDto.Emplacement emplacement(Long id) {
		return jdbc.sql("SELECT id, code, nom FROM emplacements WHERE id = :i").param("i", id)
				.query((rs, n) -> new TransfertDto.Emplacement(rs.getLong("id"), rs.getString("code"), rs.getString("nom"))).single();
	}

	private String utilisateur(Long id) {
		return id == null ? null : jdbc.sql("SELECT nom || coalesce(' ' || prenom, '') FROM utilisateurs WHERE id = :i").param("i", id).query(String.class).optional().orElse(null);
	}

	/** Les utilisateurs actifs qui peuvent réceptionner à cet emplacement. */
	private List<Long> personnelDe(Long emplacementId) {
		return jdbc.sql("""
						SELECT DISTINCT u.id FROM utilisateurs u
						JOIN utilisateur_role ur ON ur.utilisateur_id = u.id JOIN roles r ON r.id = ur.role_id
						JOIN role_permission rp ON rp.role_id = r.id JOIN permissions p ON p.id = rp.permission_id
						WHERE u.statut = 'ACTIF' AND p.code = 'transferts.modifier'
						  AND (r.perimetre_global OR EXISTS (SELECT 1 FROM emplacement_utilisateur eu WHERE eu.utilisateur_id = u.id AND eu.emplacement_id = :e))""")
				.param("e", emplacementId).query(Long.class).list();
	}

	private Optional<Long> delaiReception() {
		return parametres.entier(CatalogueParametres.DELAI_RECEPTION_TRANSFERT_JOURS);
	}

	private static boolean enRetard(String statut, Instant expedie, Optional<Long> delai) {
		return "EXPEDIE".equals(statut) && expedie != null && delai.isPresent() && expedie.isBefore(Instant.now().minus(delai.get(), ChronoUnit.DAYS));
	}

	private static String libelleStatut(String statut) {
		return switch (statut) {
			case "INITIE" -> LIBELLE_STATUT_INITIE;
			case "EXPEDIE" -> "Expédié (en transit)";
			case "RECEPTIONNE" -> "Réceptionné";
			case "ANNULE" -> "Annulé";
			default -> statut;
		};
	}

	/** Un transfert est visible depuis sa source comme depuis sa destination ; ailleurs il est introuvable (EF-ADM-13). */
	private void verifierVisible(Entete e) {
		PerimetreService.Perimetre p = perimetre();
		if (!p.global() && !p.autorise(e.sourceId()) && !p.autorise(e.destinationId())) {
			throw introuvable();
		}
	}

	private static void exigerStatut(Entete e, String attendu, String message) {
		if (!attendu.equals(e.statut())) {
			throw new ErreurMetierException("TRANSFERT_STATUT", message);
		}
	}

	private static ErreurMetierException introuvable() {
		return new ErreurMetierException("TRANSFERT_INTROUVABLE", "Transfert introuvable.", HttpStatus.NOT_FOUND);
	}

	private PerimetreService.Perimetre perimetre() {
		return perimetres.perimetreDe(acteurId());
	}

	private static Long acteurId() {
		return ActeurAuthentifie.courant().map(ActeurAuthentifie::id)
				.orElseThrow(() -> new ErreurMetierException("NON_AUTHENTIFIE", "Connexion requise.", HttpStatus.UNAUTHORIZED));
	}

	private static String vide(String valeur) {
		return valeur == null || valeur.isBlank() ? null : valeur.trim();
	}

}
