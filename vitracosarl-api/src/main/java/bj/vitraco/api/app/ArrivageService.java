package bj.vitraco.api.app;

import bj.vitraco.api.adm.utilisateurs.Utilisateur;
import bj.vitraco.api.adm.utilisateurs.UtilisateurRepository;
import bj.vitraco.api.aud.EntreeAudit;
import bj.vitraco.api.aud.JournalAudit;
import bj.vitraco.api.cat.chassis.ChassisService;
import bj.vitraco.api.common.CodeModule;
import bj.vitraco.api.common.ErreurMetierException;
import bj.vitraco.api.common.ErreurValidationException;
import bj.vitraco.api.common.security.ActeurAuthentifie;
import bj.vitraco.api.common.security.Droits;
import bj.vitraco.api.notif.DemandeNotification;
import bj.vitraco.api.notif.Notifications;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Arrivages d'une commande (EF-APP-02) et leurs frais d'importation (EF-APP-03). Ils ne modifient jamais le stock
 * (RG-06) : seul le passage des motos annoncées à « à réceptionner » est reflété, à la fin du dédouanement.
 */
@Service
public class ArrivageService {

	public record Filtre(Long commandeId, Boolean enCours, String recherche) {
	}

	/** Une commande accepte de nouveaux arrivages tant que la marchandise n'est pas entièrement reçue. */
	private static final Set<StatutCommande> OUVERTE_AUX_ARRIVAGES = EnumSet.of(StatutCommande.CONFIRMEE, StatutCommande.EN_TRANSIT,
			StatutCommande.AU_PORT, StatutCommande.DEDOUANEMENT, StatutCommande.A_RECEPTIONNER, StatutCommande.RECEPTION_PARTIELLE);

	private final ArrivageRepository arrivages;
	private final FraisArrivageRepository frais;
	private final CommandeFournisseurRepository commandes;
	private final UtilisateurRepository utilisateurs;
	private final ChassisService chassis;
	private final JdbcClient jdbc;
	private final JsonMapper json;
	private final JournalAudit journal;
	private final Notifications notifications;
	private final Clock horloge;

	ArrivageService(ArrivageRepository arrivages, FraisArrivageRepository frais, CommandeFournisseurRepository commandes,
					UtilisateurRepository utilisateurs, ChassisService chassis, JdbcClient jdbc, JsonMapper json, JournalAudit journal,
					Notifications notifications, Clock horloge) {
		this.arrivages = arrivages;
		this.frais = frais;
		this.commandes = commandes;
		this.utilisateurs = utilisateurs;
		this.chassis = chassis;
		this.jdbc = jdbc;
		this.json = json;
		this.journal = journal;
		this.notifications = notifications;
		this.horloge = horloge;
	}

	@Transactional(readOnly = true)
	public Page<ArrivageDto> lister(Filtre filtre, Pageable pagination) {
		Specification<Arrivage> spec = (racine, requete, cb) -> {
			List<Predicate> criteres = new ArrayList<>();
			if (filtre.commandeId() != null) {
				criteres.add(cb.equal(racine.get("commande").get("id"), filtre.commandeId()));
			}
			if (Boolean.TRUE.equals(filtre.enCours())) {
				criteres.add(cb.notEqual(racine.get("statutDouane"), StatutDouane.TERMINEE));
				criteres.add(racine.get("commande").get("statut").in(StatutCommande.CONFIRMEE, StatutCommande.EN_TRANSIT, StatutCommande.AU_PORT,
						StatutCommande.DEDOUANEMENT, StatutCommande.A_RECEPTIONNER));
			}
			if (filtre.recherche() != null && !filtre.recherche().isBlank()) {
				String motif = "%" + filtre.recherche().trim().toLowerCase(Locale.ROOT) + "%";
				criteres.add(cb.or(cb.like(cb.lower(racine.get("numeroBl")), motif), cb.like(cb.lower(cb.coalesce(racine.get("conteneur"), "")), motif),
						cb.like(cb.lower(racine.get("commande").get("numero")), motif),
						cb.like(cb.lower(racine.get("commande").get("fournisseur").get("raisonSociale")), motif)));
			}
			return cb.and(criteres.toArray(new Predicate[0]));
		};
		Page<Arrivage> page = arrivages.findAll(spec, pagination);
		Map<Long, Long> nombres = nbChassis(page.getContent().stream().map(Arrivage::getId).toList());
		return page.map(a -> versDto(a, nombres.getOrDefault(a.getId(), 0L), false));
	}

	@Transactional(readOnly = true)
	public ArrivageDto detail(Long id) {
		Arrivage a = trouver(id);
		return versDto(a, nbChassis(List.of(id)).getOrDefault(id, 0L), true);
	}

	@Transactional
	public ArrivageDto creer(Long commandeId, ArrivageRequete requete) {
		CommandeFournisseur commande = commandes.findById(commandeId)
				.orElseThrow(() -> new ErreurMetierException("COMMANDE_INTROUVABLE", "Commande introuvable.", HttpStatus.NOT_FOUND));
		if (!OUVERTE_AUX_ARRIVAGES.contains(commande.getStatut())) {
			throw new ErreurMetierException("COMMANDE_NON_OUVERTE_AUX_ARRIVAGES",
					"On crée un arrivage à partir de la confirmation de la commande, tant que la marchandise n'est pas entièrement reçue (statut actuel : « "
							+ commande.getStatut().libelle() + " »).");
		}
		Arrivage a = new Arrivage(commande);
		appliquer(a, requete);
		a.setCreePar(acteur());
		arrivages.saveAndFlush(a);
		journal.enregistrer(EntreeAudit.de(CodeModule.APP, "arrivage.cree", "Arrivage", String.valueOf(a.getId())).avecValeurs(null, resume(a)));
		return versDto(a, 0, true);
	}

	@Transactional
	public ArrivageDto modifier(Long id, ArrivageRequete requete) {
		Arrivage a = trouver(id);
		StatutCommande statut = a.getCommande().getStatut();
		if (statut == StatutCommande.RECEPTIONNEE || statut == StatutCommande.ANNULEE) {
			throw new ErreurMetierException("ARRIVAGE_FIGE", "Cet arrivage ne se modifie plus (commande « " + statut.libelle() + " »).");
		}
		Map<String, Object> avant = resume(a);
		appliquer(a, requete);
		a.setModifiePar(acteur());
		arrivages.saveAndFlush(a);
		journal.enregistrer(EntreeAudit.de(CodeModule.APP, "arrivage.modifie", "Arrivage", String.valueOf(id)).avecValeurs(avant, resume(a)));
		return versDto(a, nbChassis(List.of(id)).getOrDefault(id, 0L), true);
	}

	/** Fait avancer le dédouanement d'une étape. À la fin, les motos annoncées passent « à réceptionner » et le magasin est prévenu. */
	@Transactional
	public ArrivageDto avancerDouane(Long id, StatutDouane cible) {
		Arrivage a = trouver(id);
		StatutDouane suivant = a.getStatutDouane().suivant().orElseThrow(() -> new ErreurMetierException("DOUANE_TERMINEE", "Le dédouanement est déjà terminé."));
		if (cible != null && cible != suivant) {
			throw new ErreurMetierException("ETAPE_SAUTEE", "Une étape ne se saute pas : après « " + a.getStatutDouane().libelle() + " » vient « " + suivant.libelle() + " ».");
		}
		if (a.getDateArriveeReelle() == null) {
			throw new ErreurMetierException("ARRIVEE_NON_SAISIE", "La marchandise n'est pas encore arrivée : saisissez la date d'arrivée réelle avant de démarrer le dédouanement.");
		}
		StatutDouane avant = a.getStatutDouane();
		a.setStatutDouane(suivant);
		a.setModifiePar(acteur());
		arrivages.saveAndFlush(a);
		int motos = suivant == StatutDouane.TERMINEE ? chassis.marquerAReceptionner(id) : 0;
		journal.enregistrer(EntreeAudit.de(CodeModule.APP, "arrivage.douane_avancee", "Arrivage", String.valueOf(id))
				.avecValeurs(Map.of("statut_douane", avant), Map.of("statut_douane", suivant, "chassis_a_receptionner", motos)));
		if (suivant == StatutDouane.TERMINEE) {
			notifications.notifierRole("magasin", DemandeNotification.de("arrivage.a_receptionner", "Arrivage à réceptionner : " + a.getNumeroBl(),
					"L'arrivage " + a.getNumeroBl() + " de la commande " + a.getCommande().getNumero() + " est dédouané" + (motos > 0 ? " (" + motos + " moto(s) annoncée(s))" : "")
							+ " : il attend sa réception.", "/approvisionnements/arrivages/" + id));
		}
		return versDto(a, nbChassis(List.of(id)).getOrDefault(id, 0L), true);
	}

	@Transactional
	public ArrivageDto ajouterFrais(Long arrivageId, FraisRequete requete) {
		Arrivage a = trouver(arrivageId);
		exigerCommandeNonAnnulee(a);
		FraisArrivage f = new FraisArrivage(a);
		appliquer(f, requete);
		f.setCreePar(acteur());
		frais.saveAndFlush(f);
		journal.enregistrer(EntreeAudit.de(CodeModule.APP, "frais_arrivage.ajoute", "Arrivage", String.valueOf(arrivageId)).avecValeurs(null, resume(f)));
		return versDto(a, nbChassis(List.of(arrivageId)).getOrDefault(arrivageId, 0L), true);
	}

	@Transactional
	public ArrivageDto modifierFrais(Long arrivageId, Long fraisId, FraisRequete requete) {
		Arrivage a = trouver(arrivageId);
		exigerCommandeNonAnnulee(a);
		FraisArrivage f = fraisDe(arrivageId, fraisId);
		if (f.isAnnule()) {
			throw new ErreurMetierException("FRAIS_ANNULE", "Ce frais est annulé : il ne se modifie plus.");
		}
		Map<String, Object> avant = resume(f);
		appliquer(f, requete);
		f.setModifiePar(acteur());
		frais.saveAndFlush(f);
		journal.enregistrer(EntreeAudit.de(CodeModule.APP, "frais_arrivage.modifie", "Arrivage", String.valueOf(arrivageId)).avecValeurs(avant, resume(f)));
		return versDto(a, nbChassis(List.of(arrivageId)).getOrDefault(arrivageId, 0L), true);
	}

	@Transactional
	public ArrivageDto annulerFrais(Long arrivageId, Long fraisId, String motif) {
		Arrivage a = trouver(arrivageId);
		exigerCommandeNonAnnulee(a);
		FraisArrivage f = fraisDe(arrivageId, fraisId);
		if (f.isAnnule()) {
			throw new ErreurMetierException("FRAIS_ANNULE", "Ce frais est déjà annulé.");
		}
		if (motif == null || motif.isBlank()) {
			throw new ErreurValidationException("motif", "Le motif de l'annulation est obligatoire.");
		}
		Map<String, Object> avant = resume(f);
		f.annuler(motif.trim());
		f.setModifiePar(acteur());
		frais.saveAndFlush(f);
		journal.enregistrer(EntreeAudit.de(CodeModule.APP, "frais_arrivage.annule", "Arrivage", String.valueOf(arrivageId))
				.avecValeurs(avant, resume(f)).avecMotif(motif.trim()));
		return versDto(a, nbChassis(List.of(arrivageId)).getOrDefault(arrivageId, 0L), true);
	}

	private void appliquer(Arrivage a, ArrivageRequete r) {
		String bl = r.numeroBl().trim();
		if (arrivages.blUtilise(a.getCommande().getId(), bl, a.getId())) {
			throw new ErreurValidationException("numero_bl", "Ce numéro de B/L existe déjà sur cette commande.");
		}
		verifierOrdre(r.dateDepartPrevue(), r.dateArriveePrevue(), "date_arrivee_prevue", "L'arrivée prévue ne peut pas précéder le départ prévu.");
		verifierOrdre(r.dateDepartReelle(), r.dateArriveeReelle(), "date_arrivee_reelle", "L'arrivée réelle ne peut pas précéder le départ réel.");
		if (a.getStatutDouane() != StatutDouane.NON_DEMARREE && r.dateArriveeReelle() == null) {
			throw new ErreurValidationException("date_arrivee_reelle", "Le dédouanement est engagé : la date d'arrivée réelle ne peut plus être retirée.");
		}
		a.setNumeroBl(bl);
		a.setConteneur(vide(r.conteneur()));
		a.setPort(vide(r.port()));
		a.setDateDepartPrevue(r.dateDepartPrevue());
		a.setDateDepartReelle(r.dateDepartReelle());
		a.setDateArriveePrevue(r.dateArriveePrevue());
		a.setDateArriveeReelle(r.dateArriveeReelle());
		a.setObservation(vide(r.observation()));
	}

	private static void verifierOrdre(LocalDate debut, LocalDate fin, String champ, String message) {
		if (debut != null && fin != null && fin.isBefore(debut)) {
			throw new ErreurValidationException(champ, message);
		}
	}

	private void appliquer(FraisArrivage f, FraisRequete r) {
		String devise = r.devise().trim().toUpperCase(Locale.ROOT);
		BigDecimal taux = r.tauxChange();
		if (devise.equals("XOF")) {
			if (taux != null && taux.compareTo(BigDecimal.ONE) != 0) {
				throw new ErreurValidationException("taux_change", "Un frais en francs CFA (XOF) a un taux de change de 1.");
			}
			taux = BigDecimal.ONE;
		} else if (taux == null) {
			throw new ErreurValidationException("taux_change", "Le taux de change vers le franc CFA est requis pour un frais en " + devise + ".");
		}
		BigDecimal montant = r.montant().setScale(2, RoundingMode.HALF_UP);
		long fcfa = montant.multiply(taux).setScale(0, RoundingMode.HALF_UP).longValueExact();
		f.renseigner(r.type(), vide(r.libelle()) == null ? r.type().libelle() : r.libelle().trim(), montant, devise, taux, fcfa, vide(r.referencePiece()),
				r.dateFrais() == null ? LocalDate.now(horloge) : r.dateFrais());
	}

	private void exigerCommandeNonAnnulee(Arrivage a) {
		if (a.getCommande().getStatut() == StatutCommande.ANNULEE) {
			throw new ErreurMetierException("COMMANDE_ANNULEE", "La commande de cet arrivage est annulée.");
		}
	}

	private FraisArrivage fraisDe(Long arrivageId, Long fraisId) {
		return frais.findById(fraisId).filter(f -> f.getArrivage().getId().equals(arrivageId))
				.orElseThrow(() -> new ErreurMetierException("FRAIS_INTROUVABLE", "Frais introuvable.", HttpStatus.NOT_FOUND));
	}

	private Arrivage trouver(Long id) {
		return arrivages.findById(id)
				.orElseThrow(() -> new ErreurMetierException("ARRIVAGE_INTROUVABLE", "Arrivage introuvable.", HttpStatus.NOT_FOUND));
	}

	private Map<Long, Long> nbChassis(List<Long> ids) {
		Map<Long, Long> nombres = new HashMap<>();
		if (!ids.isEmpty()) {
			jdbc.sql("SELECT arrivage_id, count(*) AS n FROM chassis WHERE arrivage_id IN (:ids) GROUP BY arrivage_id").param("ids", ids)
					.query((rs, i) -> nombres.put(rs.getLong("arrivage_id"), rs.getLong("n"))).list();
		}
		return nombres;
	}

	ArrivageDto versDto(Arrivage a, long nbChassis, boolean detail) {
		boolean prix = Droits.aPermission("prix_achat.voir") || Droits.aPermission("approvisionnements.creer");
		LocalDate aujourdhui = LocalDate.now(horloge);
		String etat = a.getStatutDouane() == StatutDouane.TERMINEE ? "A_RECEPTIONNER" : a.getStatutDouane() == StatutDouane.EN_COURS ? "DEDOUANEMENT"
				: a.getDateArriveeReelle() != null ? "AU_PORT" : "EN_TRANSIT";
		String etatLibelle = switch (etat) {
			case "A_RECEPTIONNER" -> "À réceptionner";
			case "DEDOUANEMENT" -> "Dédouanement";
			case "AU_PORT" -> "Au port";
			default -> "En transit";
		};
		// Le retard se mesure tant que la marchandise n'est pas arrivée (EF-APP-09).
		long retard = etat.equals("EN_TRANSIT") && a.getDateArriveePrevue() != null && aujourdhui.isAfter(a.getDateArriveePrevue())
				? ChronoUnit.DAYS.between(a.getDateArriveePrevue(), aujourdhui) : 0;
		CommandeFournisseur c = a.getCommande();

		List<ArrivageDto.Frais> listeFrais = List.of();
		Long totalFrais = null;
		Integer sansPiece = null;
		if (prix) {
			List<FraisArrivage> bruts = frais.findByArrivageIdOrderByDateFraisDescIdDesc(a.getId());
			listeFrais = bruts.stream().map(f -> new ArrivageDto.Frais(f.getId(), f.getType(), f.getType().libelle(), f.getLibelle(), f.getMontant(), f.getDevise(),
					f.getTauxChange(), f.getMontantFcfa(), f.getReferencePiece(), f.getReferencePiece() == null, f.getDateFrais(), f.isAnnule(), f.getMotifAnnulation())).toList();
			totalFrais = bruts.stream().filter(f -> !f.isAnnule()).mapToLong(FraisArrivage::getMontantFcfa).sum();
			sansPiece = (int) bruts.stream().filter(f -> !f.isAnnule() && f.getReferencePiece() == null).count();
		}
		return new ArrivageDto(a.getId(), c.getId(), c.getNumero(), c.getFournisseur().getRaisonSociale(), a.getNumeroBl(), a.getConteneur(), a.getPort(),
				a.getDateDepartPrevue(), a.getDateDepartReelle(), a.getDateArriveePrevue(), a.getDateArriveeReelle(), a.getStatutDouane(),
				a.getStatutDouane().libelle(), etat, etatLibelle, retard, a.getObservation(), nbChassis, detail ? chassisAnnonces(a.getId()) : List.of(),
				prix, listeFrais, totalFrais, sansPiece, detail ? imports(a.getId()) : List.of(), a.getCreeLe());
	}

	private List<ArrivageDto.ChassisAnnonce> chassisAnnonces(Long arrivageId) {
		return jdbc.sql("""
						SELECT c.id, c.numero, m.code_article, c.statut FROM chassis c JOIN modeles m ON m.id = c.modele_id
						WHERE c.arrivage_id = :a ORDER BY c.numero LIMIT 1000""")
				.param("a", arrivageId)
				.query((rs, n) -> new ArrivageDto.ChassisAnnonce(rs.getLong("id"), rs.getString("numero"), rs.getString("code_article"), rs.getString("statut"),
						bj.vitraco.api.cat.chassis.StatutChassis.valueOf(rs.getString("statut")).libelle()))
				.list();
	}

	private List<ArrivageDto.Import> imports(Long arrivageId) {
		return jdbc.sql("""
						SELECT i.id, i.nom_fichier, i.nb_lignes, i.nb_importees, i.nb_rejetees, i.rejets::text AS rejets, i.cree_le,
						       trim(coalesce(u.prenom || ' ', '') || coalesce(u.nom, '')) AS auteur
						FROM imports_colisage i LEFT JOIN utilisateurs u ON u.id = i.cree_par
						WHERE i.arrivage_id = :a ORDER BY i.cree_le DESC, i.id DESC""")
				.param("a", arrivageId)
				.query((rs, n) -> new ArrivageDto.Import(rs.getLong("id"), rs.getString("nom_fichier"), rs.getInt("nb_lignes"), rs.getInt("nb_importees"),
						rs.getInt("nb_rejetees"), json.readValue(rs.getString("rejets"), new TypeReference<List<ArrivageDto.Rejet>>() {
						}), rs.getString("auteur"), rs.getTimestamp("cree_le").toInstant()))
				.list();
	}

	private Utilisateur acteur() {
		return ActeurAuthentifie.courant().flatMap(x -> utilisateurs.findById(x.id())).orElse(null);
	}

	private static String vide(String valeur) {
		return valeur == null || valeur.isBlank() ? null : valeur.trim();
	}

	private static Map<String, Object> resume(Arrivage a) {
		Map<String, Object> v = new LinkedHashMap<>();
		v.put("numero_bl", a.getNumeroBl());
		v.put("conteneur", a.getConteneur());
		v.put("port", a.getPort());
		v.put("depart_prevu", a.getDateDepartPrevue());
		v.put("depart_reel", a.getDateDepartReelle());
		v.put("arrivee_prevue", a.getDateArriveePrevue());
		v.put("arrivee_reelle", a.getDateArriveeReelle());
		return v;
	}

	private static Map<String, Object> resume(FraisArrivage f) {
		Map<String, Object> v = new LinkedHashMap<>();
		v.put("type", f.getType());
		v.put("libelle", f.getLibelle());
		v.put("montant", f.getMontant());
		v.put("devise", f.getDevise());
		v.put("taux_change", f.getTauxChange());
		v.put("montant_fcfa", f.getMontantFcfa());
		v.put("piece", f.getReferencePiece());
		v.put("annule", f.isAnnule());
		return v;
	}

}
