package bj.vitraco.api.rec;

import bj.vitraco.api.adm.emplacements.PerimetreService;
import bj.vitraco.api.adm.emplacements.TypeEmplacement;
import bj.vitraco.api.adm.numerotation.NumerotationService;
import bj.vitraco.api.adm.numerotation.TypeDocument;
import bj.vitraco.api.adm.parametres.CatalogueParametres;
import bj.vitraco.api.adm.parametres.ParametreService;
import bj.vitraco.api.app.CommandeFournisseurService;
import bj.vitraco.api.app.StatutCommande;
import bj.vitraco.api.app.StatutDouane;
import bj.vitraco.api.aud.EntreeAudit;
import bj.vitraco.api.aud.JournalAudit;
import bj.vitraco.api.cat.Modele;
import bj.vitraco.api.cat.ModeleRepository;
import bj.vitraco.api.cat.chassis.Chassis;
import bj.vitraco.api.cat.chassis.ChassisRepository;
import bj.vitraco.api.cat.chassis.ChassisService;
import bj.vitraco.api.cat.chassis.CreationChassis;
import bj.vitraco.api.cat.chassis.NumeroChassis;
import bj.vitraco.api.cat.chassis.StatutChassis;
import bj.vitraco.api.common.CodeModule;
import bj.vitraco.api.common.ErreurMetierException;
import bj.vitraco.api.common.ErreurValidationException;
import bj.vitraco.api.common.security.ActeurAuthentifie;
import bj.vitraco.api.notif.DemandeNotification;
import bj.vitraco.api.notif.Notifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Réception d'un arrivage au magasin (SRS 5.4). Le magasinier ouvre une réception en brouillon et scanne les châssis ; rien ne
 * bouge dans le stock tant qu'un second utilisateur n'a pas validé (EF-REC-05, RG-19). À la validation, les motos entrent
 * « disponibles » (ou « bloquées » si elles sont endommagées), la commande passe « réception partielle » puis
 * « réceptionnée » (RG-07), les mouvements sont écrits et le PV peut être édité.
 */
@Service
public class ReceptionService {

	public record Filtre(String statut, Long arrivageId, Long emplacementId, String recherche) {
	}

	private record Entete(Long id, String numero, Long arrivageId, Long emplacementId, String statut, String observation, Long creePar, Instant creeLe,
						  Long validePar, Instant valideLe, String resume) {
	}

	private final JdbcClient jdbc;
	private final JsonMapper json;
	private final ChassisRepository chassis;
	private final ChassisService chassisService;
	private final ModeleRepository modeles;
	private final CommandeFournisseurService commandes;
	private final NumerotationService numerotation;
	private final PerimetreService perimetres;
	private final ParametreService parametres;
	private final JournalAudit journal;
	private final Notifications notifications;

	ReceptionService(JdbcClient jdbc, JsonMapper json, ChassisRepository chassis, ChassisService chassisService, ModeleRepository modeles,
					 CommandeFournisseurService commandes, NumerotationService numerotation, PerimetreService perimetres, ParametreService parametres,
					 JournalAudit journal, Notifications notifications) {
		this.jdbc = jdbc;
		this.json = json;
		this.chassis = chassis;
		this.chassisService = chassisService;
		this.modeles = modeles;
		this.commandes = commandes;
		this.numerotation = numerotation;
		this.perimetres = perimetres;
		this.parametres = parametres;
		this.journal = journal;
		this.notifications = notifications;
	}

	// ---- Lecture -------------------------------------------------------------------------------------------------------

	@Transactional(readOnly = true)
	public Page<ReceptionDto.Liste> lister(Filtre filtre, Pageable pagination) {
		PerimetreService.Perimetre perimetre = perimetre();
		List<String> criteres = new ArrayList<>();
		Map<String, Object> params = new HashMap<>();
		if (!perimetre.global()) {
			if (perimetre.emplacementIds().isEmpty()) {
				return new PageImpl<>(List.of(), pagination, 0);
			}
			criteres.add("r.emplacement_id IN (:perimetre)");
			params.put("perimetre", perimetre.emplacementIds());
		}
		if (filtre.statut() != null && !filtre.statut().isBlank()) {
			criteres.add("r.statut = :statut");
			params.put("statut", filtre.statut());
		}
		if (filtre.arrivageId() != null) {
			criteres.add("r.arrivage_id = :arrivage");
			params.put("arrivage", filtre.arrivageId());
		}
		if (filtre.emplacementId() != null) {
			criteres.add("r.emplacement_id = :emplacement");
			params.put("emplacement", filtre.emplacementId());
		}
		if (filtre.recherche() != null && !filtre.recherche().isBlank()) {
			criteres.add("(lower(r.numero) LIKE :q OR lower(a.numero_bl) LIKE :q OR lower(c.numero) LIKE :q)");
			params.put("q", "%" + filtre.recherche().trim().toLowerCase(Locale.ROOT) + "%");
		}
		String ou = criteres.isEmpty() ? "" : " WHERE " + String.join(" AND ", criteres);
		String base = " FROM receptions r JOIN arrivages a ON a.id = r.arrivage_id JOIN commandes_fournisseur c ON c.id = a.commande_id"
				+ " JOIN emplacements e ON e.id = r.emplacement_id" + ou;
		long total = jdbc.sql("SELECT count(*)" + base).params(params).query(Long.class).single();
		var contenu = jdbc.sql("SELECT r.id, r.numero, r.statut, a.numero_bl, c.numero AS commande, e.nom AS emplacement, r.cree_le, r.valide_le,"
						+ " (SELECT count(*) FROM lignes_reception l WHERE l.reception_id = r.id) AS nb" + base
						+ " ORDER BY r.cree_le DESC, r.id DESC LIMIT :limite OFFSET :decalage")
				.params(params).param("limite", pagination.getPageSize()).param("decalage", pagination.getOffset())
				.query((rs, n) -> new ReceptionDto.Liste(rs.getLong("id"), rs.getString("numero"), rs.getString("statut"), rs.getString("numero_bl"),
						rs.getString("commande"), rs.getString("emplacement"), rs.getInt("nb"), rs.getTimestamp("cree_le").toInstant(),
						rs.getTimestamp("valide_le") == null ? null : rs.getTimestamp("valide_le").toInstant()))
				.list();
		return new PageImpl<>(contenu, pagination, total);
	}

	/** Les arrivages dédouanés dont les motos attendent d'être reçues : c'est la liste de travail du magasinier. */
	@Transactional(readOnly = true)
	public List<ReceptionDto.ArrivageAReceptionner> arrivagesAReceptionner() {
		return jdbc.sql("""
						SELECT a.id, a.numero_bl, c.id AS commande_id, c.numero AS commande, f.raison_sociale,
						       (SELECT count(*) FROM chassis x WHERE x.arrivage_id = a.id) AS nb_annonces,
						       (SELECT count(*) FROM chassis x WHERE x.arrivage_id = a.id AND x.statut = 'A_RECEPTIONNER') AS restant_annonce,
						       (SELECT coalesce(sum(greatest(l.quantite - l.quantite_recue, 0)), 0) FROM lignes_commande_fournisseur l WHERE l.commande_id = c.id) AS reliquat,
						       r.id AS reception_id, r.numero AS reception_numero
						FROM arrivages a
						JOIN commandes_fournisseur c ON c.id = a.commande_id
						JOIN fournisseurs f ON f.id = c.fournisseur_id
						LEFT JOIN receptions r ON r.arrivage_id = a.id AND r.statut = 'BROUILLON'
						WHERE a.statut_douane = 'TERMINEE' AND c.statut IN ('A_RECEPTIONNER', 'RECEPTION_PARTIELLE')
						ORDER BY a.date_arrivee_reelle, a.id""")
				.query((rs, n) -> {
					boolean colisage = rs.getLong("nb_annonces") > 0;
					int restant = (int) (colisage ? rs.getLong("restant_annonce") : rs.getLong("reliquat"));
					return new ReceptionDto.ArrivageAReceptionner(rs.getLong("id"), rs.getString("numero_bl"), rs.getLong("commande_id"), rs.getString("commande"),
							rs.getString("raison_sociale"), restant, colisage, (Long) rs.getObject("reception_id"), rs.getString("reception_numero"));
				}).list().stream().filter(a -> a.restant() > 0 || a.receptionEnCoursId() != null).toList();
	}

	@Transactional(readOnly = true)
	public ReceptionDto detail(Long id) {
		Entete e = charger(id);
		verifierPerimetre(e);
		return construire(e);
	}

	// ---- Création et scan ----------------------------------------------------------------------------------------------

	@Transactional
	public ReceptionDto creer(ReceptionRequete requete) {
		var arrivage = jdbc.sql("""
						SELECT a.id, a.statut_douane, c.id AS commande_id, c.statut AS commande_statut
						FROM arrivages a JOIN commandes_fournisseur c ON c.id = a.commande_id WHERE a.id = :a""")
				.param("a", requete.arrivageId()).query((rs, n) -> new String[]{rs.getString("statut_douane"), rs.getString("commande_statut")}).optional()
				.orElseThrow(() -> new ErreurValidationException("arrivage_id", "Arrivage inconnu."));
		StatutCommande statutCommande = StatutCommande.valueOf(arrivage[1]);
		if (!StatutDouane.TERMINEE.name().equals(arrivage[0]) || !statutCommande.recoitDesReceptions()) {
			throw new ErreurMetierException("RECEPTION_IMPOSSIBLE",
					"Cet arrivage n'est pas à réceptionner : son dédouanement doit être terminé et la commande au statut « À réceptionner ».");
		}
		var magasin = jdbc.sql("SELECT id, code, type, actif FROM emplacements WHERE id = :e").param("e", requete.emplacementId())
				.query((rs, n) -> new Object[]{rs.getString("code"), rs.getString("type"), rs.getBoolean("actif")}).optional()
				.orElseThrow(() -> new ErreurValidationException("emplacement_id", "Emplacement inconnu."));
		if (!TypeEmplacement.MAGASIN.name().equals(magasin[1]) || !(boolean) magasin[2]) {
			throw new ErreurValidationException("emplacement_id", "Les motos se reçoivent dans un magasin actif.");
		}
		PerimetreService.Perimetre perimetre = perimetre();
		if (!perimetre.global() && !perimetre.autorise(requete.emplacementId())) {
			throw new ErreurMetierException("HORS_PERIMETRE", "Ce magasin ne fait pas partie de vos emplacements.", HttpStatus.FORBIDDEN);
		}
		Optional<String> enCours = jdbc.sql("SELECT numero FROM receptions WHERE arrivage_id = :a AND statut = 'BROUILLON'").param("a", requete.arrivageId())
				.query(String.class).optional();
		if (enCours.isPresent()) {
			throw new ErreurMetierException("RECEPTION_EN_COURS", "Une réception est déjà ouverte pour cet arrivage : " + enCours.get() + ". Validez-la avant d'en ouvrir une autre.");
		}

		String numero = numerotation.prochainNumero(TypeDocument.RECEPTION, (String) magasin[0]);
		Long id = jdbc.sql("INSERT INTO receptions (numero, arrivage_id, emplacement_id, observation, cree_par) VALUES (:n, :a, :e, :o, :u) RETURNING id")
				.param("n", numero).param("a", requete.arrivageId()).param("e", requete.emplacementId()).param("o", vide(requete.observation()))
				.param("u", acteurId()).query(Long.class).single();
		ReceptionDto dto = construire(charger(id));
		if (dto.nbAttendus() == 0) {
			throw new ErreurMetierException("RIEN_A_RECEPTIONNER", "Plus rien à réceptionner sur cet arrivage.");
		}
		journal.enregistrer(EntreeAudit.de(CodeModule.REC, "reception.ouverte", "Reception", String.valueOf(id))
				.avecValeurs(null, Map.of("numero", numero, "arrivage", dto.arrivage().numeroBl(), "magasin", magasin[0])));
		return dto;
	}

	@Transactional
	public ReceptionDto scanner(Long id, ScanRequete scan) {
		Entete e = charger(id);
		exigerBrouillon(e);
		verifierPerimetre(e);

		String numero = NumeroChassis.normaliser(scan.numero());
		if (!NumeroChassis.valide(numero)) {
			throw new ErreurValidationException("numero", "Numéro de châssis invalide : 3 à 40 lettres ou chiffres (espaces et tirets ignorés).");
		}
		if (jdbc.sql("SELECT count(*) FROM lignes_reception WHERE reception_id = :r AND numero = :n").param("r", id).param("n", numero).query(Long.class).single() > 0) {
			throw new ErreurValidationException("numero", "Le châssis " + numero + " est déjà scanné dans cette réception.");
		}
		var commande = jdbc.sql("SELECT c.id FROM arrivages a JOIN commandes_fournisseur c ON c.id = a.commande_id WHERE a.id = :a").param("a", e.arrivageId())
				.query(Long.class).single();
		boolean endommage = Boolean.TRUE.equals(scan.endommage());
		String motif = vide(scan.motif());

		Optional<Chassis> existant = chassis.findByNumero(numero);
		Long modeleId;
		Long chassisId = null;
		String nature;
		if (existant.isPresent()) {
			Chassis c = existant.get();
			boolean annonceIci = c.getStatut() == StatutChassis.A_RECEPTIONNER && e.arrivageId().equals(c.getArrivageId());
			if (!annonceIci) {
				throw new ErreurValidationException("numero", c.getStatut() == StatutChassis.A_RECEPTIONNER
						? "Le châssis " + numero + " est annoncé sur un autre arrivage."
						: "Le châssis " + numero + " existe déjà : " + c.getStatut().libelle() + (c.getEmplacement() == null ? "" : " à " + c.getEmplacement().getNom()) + ".");
			}
			if (scan.codeArticle() != null && !scan.codeArticle().isBlank() && !c.getModele().getCodeArticle().equalsIgnoreCase(scan.codeArticle().trim())) {
				throw new ErreurValidationException("code_article", "Modèle différent de l'attendu : ce châssis est annoncé comme " + c.getModele().getCodeArticle() + ".");
			}
			modeleId = c.getModele().getId();
			chassisId = c.getId();
			nature = "ANNONCE";
		} else {
			if (scan.codeArticle() == null || scan.codeArticle().isBlank()) {
				throw new ErreurValidationException("code_article", "Ce châssis n'est pas annoncé : indiquez son modèle (code article).");
			}
			Modele modele = modeles.findByCodeArticle(scan.codeArticle().trim())
					.orElseThrow(() -> new ErreurValidationException("code_article", "Code article inconnu : " + scan.codeArticle().trim() + "."));
			Integer reliquat = jdbc.sql("SELECT quantite - quantite_recue FROM lignes_commande_fournisseur WHERE commande_id = :c AND modele_id = :m")
					.param("c", commande).param("m", modele.getId()).query(Integer.class).optional()
					.orElseThrow(() -> new ErreurValidationException("code_article", "Le modèle " + modele.getCodeArticle() + " ne figure pas sur la commande de cet arrivage."));
			chassisService.controler(numero, modele.getId()).ifPresent(refus -> {
				throw new ErreurValidationException("numero", refus.substring(0, 1).toUpperCase(Locale.ROOT) + refus.substring(1) + ".");
			});
			long dejaScannes = jdbc.sql("SELECT count(*) FROM lignes_reception WHERE reception_id = :r AND modele_id = :m").param("r", id).param("m", modele.getId())
					.query(Long.class).single();
			boolean colisage = jdbc.sql("SELECT count(*) FROM chassis WHERE arrivage_id = :a").param("a", e.arrivageId()).query(Long.class).single() > 0;
			// Sans colisage, tout est « nouveau » tant que la quantité commandée n'est pas dépassée ; avec colisage ou au-delà, c'est un inattendu.
			boolean surplus = dejaScannes >= reliquat;
			nature = colisage || surplus ? "INATTENDU" : "NOUVEAU";
			modeleId = modele.getId();
		}
		if ("INATTENDU".equals(nature) && motif == null) {
			throw new ErreurValidationException("motif", "Ce châssis n'était pas annoncé : justifiez son acceptation (il sera signalé).");
		}
		if (endommage && motif == null) {
			throw new ErreurValidationException("motif", "Indiquez le dommage constaté : la moto entrera bloquée.");
		}
		jdbc.sql("""
						INSERT INTO lignes_reception (reception_id, numero, modele_id, chassis_id, nature, endommage, motif, cree_par)
						VALUES (:r, :n, :m, :c, :nat, :d, :motif, :u)""")
				.param("r", id).param("n", numero).param("m", modeleId).param("c", chassisId).param("nat", nature).param("d", endommage)
				.param("motif", motif).param("u", acteurId()).update();
		return construire(charger(id));
	}

	@Transactional
	public ReceptionDto retirer(Long id, Long ligneId) {
		Entete e = charger(id);
		exigerBrouillon(e);
		verifierPerimetre(e);
		String numero = jdbc.sql("SELECT numero FROM lignes_reception WHERE id = :l AND reception_id = :r").param("l", ligneId).param("r", id).query(String.class).optional()
				.orElseThrow(() -> new ErreurMetierException("LIGNE_INTROUVABLE", "Ligne introuvable.", HttpStatus.NOT_FOUND));
		jdbc.sql("DELETE FROM lignes_reception WHERE id = :l").param("l", ligneId).update();
		journal.enregistrer(EntreeAudit.de(CodeModule.REC, "reception.scan_retire", "Reception", String.valueOf(id)).avecValeurs(Map.of("numero", numero), null));
		return construire(charger(id));
	}

	// ---- Validation ----------------------------------------------------------------------------------------------------

	@Transactional
	public ReceptionDto valider(Long id) {
		Entete e = jdbc.sql("SELECT * FROM receptions WHERE id = :i FOR UPDATE").param("i", id).query((rs, n) -> versEntete(rs)).optional()
				.orElseThrow(() -> new ErreurMetierException("RECEPTION_INTROUVABLE", "Réception introuvable.", HttpStatus.NOT_FOUND));
		exigerBrouillon(e);
		verifierPerimetre(e);
		Long validateur = acteurId();
		if (parametres.booleen(CatalogueParametres.RECEPTION_UTILISATEURS_DIFFERENTS).orElse(true) && validateur.equals(e.creePar())) {
			throw new ErreurMetierException("UTILISATEUR_IDENTIQUE",
					"Une réception doit être validée par un autre utilisateur que celui qui l'a ouverte.", HttpStatus.FORBIDDEN);
		}
		ReceptionDto avant = construire(e); // rapprochement figé avant que les motos ne changent d'état
		if (avant.lignes().isEmpty()) {
			throw new ErreurMetierException("RECEPTION_VIDE", "Aucun châssis n'a été scanné : il n'y a rien à valider.");
		}
		Long commandeId = jdbc.sql("SELECT commande_id FROM arrivages WHERE id = :a").param("a", e.arrivageId()).query(Long.class).single();

		for (ReceptionDto.Ligne l : avant.lignes()) {
			Long chassisId = jdbc.sql("SELECT chassis_id FROM lignes_reception WHERE id = :l").param("l", l.id()).query(Long.class).optional().orElse(null);
			if (chassisId != null) {
				chassisService.receptionner(chassisId, e.emplacementId(), l.endommage(), l.motif(), id);
			} else {
				Chassis cree = chassisService.creer(new CreationChassis(l.numero(), l.modeleId(), l.endommage() ? StatutChassis.BLOQUEE : StatutChassis.DISPONIBLE,
						e.emplacementId(), e.arrivageId(), id, null, null, null, "RECEPTION", id, l.motif()));
				jdbc.sql("UPDATE lignes_reception SET chassis_id = :c WHERE id = :l").param("c", cree.getId()).param("l", l.id()).update();
			}
		}
		// La commande cumule les motos reçues par modèle : réception partielle, puis réceptionnée (RG-07). Le stock, lui, vient des châssis.
		Map<Long, Integer> parModele = new TreeMap<>();
		avant.lignes().forEach(l -> parModele.merge(l.modeleId(), 1, Integer::sum));
		parModele.forEach((modeleId, nombre) -> commandes.enregistrerReception(commandeId, modeleId, nombre));

		jdbc.sql("""
						UPDATE receptions SET statut = 'VALIDEE', valide_par = :u, valide_le = now(), modifie_le = now(), modifie_par = :u,
						       resume = CAST(:resume AS jsonb)
						WHERE id = :i AND statut = 'BROUILLON'""")
				.param("u", validateur).param("i", id).param("resume", json.writeValueAsString(Map.of("modeles", avant.resume(), "manquants", avant.manquants()))).update();

		journal.enregistrer(EntreeAudit.de(CodeModule.REC, "reception.validee", "Reception", String.valueOf(id))
				.avecValeurs(Map.of("statut", "BROUILLON"), Map.of("statut", "VALIDEE", "recus", avant.nbRecus(), "attendus", avant.nbAttendus(),
						"bloques", avant.nbBloques(), "inattendus", avant.nbInattendus(), "manquants", avant.manquants().size())));
		if (avant.nbInattendus() > 0 || avant.nbBloques() > 0) {
			// Le contrôle ouvrira ses anomalies avec le module ANO ; d'ici là, il est prévenu.
			notifications.notifierRole("controleur", DemandeNotification.de("reception.ecart", "Écart à la réception " + avant.numero(),
					avant.nbInattendus() + " moto(s) non annoncée(s) acceptée(s) et " + avant.nbBloques() + " moto(s) bloquée(s) à la réception " + avant.numero()
							+ " (arrivage " + avant.arrivage().numeroBl() + ").", "/stock/receptions/" + id));
		}
		return construire(charger(id));
	}

	// ---- Construction du rapprochement ---------------------------------------------------------------------------------

	private ReceptionDto construire(Entete e) {
		var arrivage = jdbc.sql("""
						SELECT a.id, a.numero_bl, c.id AS commande_id, c.numero AS commande, f.raison_sociale
						FROM arrivages a JOIN commandes_fournisseur c ON c.id = a.commande_id JOIN fournisseurs f ON f.id = c.fournisseur_id WHERE a.id = :a""")
				.param("a", e.arrivageId())
				.query((rs, n) -> new ReceptionDto.Arrivage(rs.getLong("id"), rs.getString("numero_bl"), rs.getLong("commande_id"), rs.getString("commande"), rs.getString("raison_sociale")))
				.single();
		var emplacement = jdbc.sql("SELECT id, code, nom FROM emplacements WHERE id = :e").param("e", e.emplacementId())
				.query((rs, n) -> new ReceptionDto.Emplacement(rs.getLong("id"), rs.getString("code"), rs.getString("nom"))).single();
		List<ReceptionDto.Ligne> lignes = jdbc.sql("""
						SELECT l.id, l.numero, l.modele_id, m.code_article, m.marque || ' ' || m.nom AS modele, l.nature, l.endommage, l.motif, l.cree_le,
						       trim(coalesce(u.prenom || ' ', '') || coalesce(u.nom, '')) AS auteur
						FROM lignes_reception l JOIN modeles m ON m.id = l.modele_id LEFT JOIN utilisateurs u ON u.id = l.cree_par
						WHERE l.reception_id = :r ORDER BY l.cree_le DESC, l.id DESC""")
				.param("r", e.id())
				.query((rs, n) -> new ReceptionDto.Ligne(rs.getLong("id"), rs.getString("numero"), rs.getLong("modele_id"), rs.getString("code_article"), rs.getString("modele"),
						rs.getString("nature"), rs.getBoolean("endommage"), rs.getString("motif"), rs.getString("auteur"), rs.getTimestamp("cree_le").toInstant()))
				.list();

		List<ReceptionDto.ResumeModele> resume;
		List<String> manquants;
		if (e.resume() != null) {
			var figé = json.readValue(e.resume(), new TypeReference<Map<String, Object>>() {
			});
			resume = json.convertValue(figé.get("modeles"), new TypeReference<List<ReceptionDto.ResumeModele>>() {
			});
			manquants = json.convertValue(figé.get("manquants"), new TypeReference<List<String>>() {
			});
		} else {
			// En brouillon : attendu = motos annoncées encore à réceptionner ; sans colisage, le reliquat de la commande.
			Map<Long, int[]> parModele = new LinkedHashMap<>(); // modele -> {attendu, recu}
			Map<Long, String[]> libelles = new HashMap<>();
			boolean colisage = jdbc.sql("SELECT count(*) FROM chassis WHERE arrivage_id = :a").param("a", e.arrivageId()).query(Long.class).single() > 0;
			if (colisage) {
				jdbc.sql("""
								SELECT c.modele_id, m.code_article, m.marque || ' ' || m.nom AS libelle, count(*) AS n
								FROM chassis c JOIN modeles m ON m.id = c.modele_id WHERE c.arrivage_id = :a AND c.statut = 'A_RECEPTIONNER' GROUP BY c.modele_id, m.code_article, m.marque, m.nom""")
						.param("a", e.arrivageId()).query((rs, n) -> {
							parModele.computeIfAbsent(rs.getLong("modele_id"), k -> new int[2])[0] = rs.getInt("n");
							libelles.put(rs.getLong("modele_id"), new String[]{rs.getString("code_article"), rs.getString("libelle")});
							return null;
						}).list();
			} else {
				jdbc.sql("""
								SELECT l.modele_id, m.code_article, m.marque || ' ' || m.nom AS libelle, greatest(l.quantite - l.quantite_recue, 0) AS n
								FROM lignes_commande_fournisseur l JOIN modeles m ON m.id = l.modele_id
								WHERE l.commande_id = :c""")
						.param("c", arrivage.commandeId()).query((rs, n) -> {
							if (rs.getInt("n") > 0) {
								parModele.computeIfAbsent(rs.getLong("modele_id"), k -> new int[2])[0] = rs.getInt("n");
								libelles.put(rs.getLong("modele_id"), new String[]{rs.getString("code_article"), rs.getString("libelle")});
							}
							return null;
						}).list();
			}
			for (ReceptionDto.Ligne l : lignes) {
				parModele.computeIfAbsent(l.modeleId(), k -> new int[2])[1]++;
				libelles.putIfAbsent(l.modeleId(), new String[]{l.codeArticle(), l.modele()});
			}
			resume = parModele.entrySet().stream().map(en -> new ReceptionDto.ResumeModele(en.getKey(), libelles.get(en.getKey())[0], libelles.get(en.getKey())[1],
					en.getValue()[0], en.getValue()[1], en.getValue()[1] - en.getValue()[0])).toList();
			Set<String> scannes = new LinkedHashSet<>();
			lignes.forEach(l -> scannes.add(l.numero()));
			manquants = colisage ? jdbc.sql("SELECT numero FROM chassis WHERE arrivage_id = :a AND statut = 'A_RECEPTIONNER' ORDER BY numero").param("a", e.arrivageId())
					.query(String.class).list().stream().filter(nu -> !scannes.contains(nu)).toList() : List.of();
		}
		int attendus = resume.stream().mapToInt(ReceptionDto.ResumeModele::attendu).sum();
		int bloques = (int) lignes.stream().filter(ReceptionDto.Ligne::endommage).count();
		int inattendus = (int) lignes.stream().filter(l -> "INATTENDU".equals(l.nature())).count();
		return new ReceptionDto(e.id(), e.numero(), e.statut(), arrivage, emplacement, e.observation(), resume, lignes, manquants, attendus, lignes.size(), bloques, inattendus,
				"BROUILLON".equals(e.statut()) && !lignes.isEmpty(), nomUtilisateur(e.creePar()), e.creeLe(), e.validePar() == null ? null : nomUtilisateur(e.validePar()), e.valideLe());
	}

	private String nomUtilisateur(Long id) {
		return jdbc.sql("SELECT trim(coalesce(prenom || ' ', '') || nom) FROM utilisateurs WHERE id = :i").param("i", id).query(String.class).optional().orElse(null);
	}

	private Entete charger(Long id) {
		return jdbc.sql("SELECT * FROM receptions WHERE id = :i").param("i", id).query((rs, n) -> versEntete(rs)).optional()
				.orElseThrow(() -> new ErreurMetierException("RECEPTION_INTROUVABLE", "Réception introuvable.", HttpStatus.NOT_FOUND));
	}

	private static Entete versEntete(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new Entete(rs.getLong("id"), rs.getString("numero"), rs.getLong("arrivage_id"), rs.getLong("emplacement_id"), rs.getString("statut"), rs.getString("observation"),
				rs.getLong("cree_par"), rs.getTimestamp("cree_le").toInstant(), (Long) rs.getObject("valide_par"),
				rs.getTimestamp("valide_le") == null ? null : rs.getTimestamp("valide_le").toInstant(), rs.getString("resume"));
	}

	private static void exigerBrouillon(Entete e) {
		if (!"BROUILLON".equals(e.statut())) {
			throw new ErreurMetierException("RECEPTION_VALIDEE", "Cette réception est validée : elle ne se modifie plus. Une erreur se corrige par inversion.");
		}
	}

	/** Une réception se lit et se traite depuis ses propres emplacements ; ailleurs elle est introuvable (EF-ADM-13). */
	private void verifierPerimetre(Entete e) {
		PerimetreService.Perimetre p = perimetre();
		if (!p.global() && !p.autorise(e.emplacementId())) {
			throw new ErreurMetierException("RECEPTION_INTROUVABLE", "Réception introuvable.", HttpStatus.NOT_FOUND);
		}
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
