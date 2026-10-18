package bj.vitraco.api.cat.chassis;

import bj.vitraco.api.adm.emplacements.EmplacementRef;
import bj.vitraco.api.adm.emplacements.PerimetreService;
import bj.vitraco.api.aud.EntreeAudit;
import bj.vitraco.api.aud.JournalAudit;
import bj.vitraco.api.common.CodeModule;
import bj.vitraco.api.common.ErreurMetierException;
import bj.vitraco.api.common.security.ActeurAuthentifie;
import bj.vitraco.api.common.security.Droits;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Liste, fiche et export des châssis. Chaque lecture est bornée au périmètre de l'utilisateur (EF-ADM-13) : un châssis
 * hors de ses emplacements est introuvable pour lui, sans que l'on révèle qu'il existe.
 */
@Service
public class ChassisConsultation {

	public record Filtre(String recherche, StatutChassis statut, Long modeleId, Long emplacementId, Long arrivageId, Integer ancienneteMin) {
	}

	private static final int EXPORT_MAX = 50_000;

	private final ChassisRepository chassis;
	private final PerimetreService perimetres;
	private final MouvementsStock mouvements;
	private final CorrectionNumeroService corrections;
	private final JdbcClient jdbc;
	private final JournalAudit journal;
	private final Clock horloge;

	ChassisConsultation(ChassisRepository chassis, PerimetreService perimetres, MouvementsStock mouvements, CorrectionNumeroService corrections,
						JdbcClient jdbc, JournalAudit journal, Clock horloge) {
		this.chassis = chassis;
		this.perimetres = perimetres;
		this.mouvements = mouvements;
		this.corrections = corrections;
		this.jdbc = jdbc;
		this.journal = journal;
		this.horloge = horloge;
	}

	static ErreurMetierException introuvable() {
		return new ErreurMetierException("CHASSIS_INTROUVABLE", "Châssis introuvable.", HttpStatus.NOT_FOUND);
	}

	@Transactional(readOnly = true)
	public Page<ChassisDto> lister(Filtre filtre, Pageable pagination) {
		return chassis.findAll(specification(filtre), pagination).map(this::versDto);
	}

	@Transactional(readOnly = true)
	public ChassisFicheDto fiche(Long id) {
		Chassis c = chassis.findById(id).filter(this::dansLePerimetre).orElseThrow(ChassisConsultation::introuvable);
		return new ChassisFicheDto(versDto(c), mouvements.historique(id), corrections.deLaFiche(id));
	}

	/** Export CSV (séparateur « ; », ouvrable dans Excel) de la liste filtrée (EF-CAT-06). Tracé au journal. */
	@Transactional
	public String exporter(Filtre filtre) {
		List<Chassis> lignes = chassis.findAll(specification(filtre), PageRequest.of(0, EXPORT_MAX, Sort.by("numero"))).getContent();
		boolean cout = Droits.aPermission("prix_achat.voir");
		StringBuilder csv = new StringBuilder("﻿Numéro;Modèle;Code article;Statut;Emplacement;Date d'entrée;Ancienneté (jours);Couleur;N° moteur");
		csv.append(cout ? ";Coût de revient\n" : "\n");
		for (Chassis c : lignes) {
			ChassisDto d = versDto(c);
			csv.append(String.join(";", cellule(d.numero()), cellule(d.modele()), cellule(d.codeArticle()), cellule(d.statutLibelle()),
					cellule(d.emplacement() == null ? "" : d.emplacement().nom()), cellule(d.dateEntree() == null ? "" : d.dateEntree().toString()),
					cellule(d.ancienneteJours() == null ? "" : d.ancienneteJours().toString()), cellule(d.couleur()), cellule(d.numeroMoteur())));
			csv.append(cout ? ";" + cellule(d.coutRevient() == null ? "" : d.coutRevient().toString()) : "").append('\n');
		}
		journal.enregistrer(EntreeAudit.de(CodeModule.CAT, "chassis.exporte", "Chassis", null)
				.avecValeurs(null, java.util.Map.of("lignes", lignes.size())));
		return csv.toString();
	}

	private Specification<Chassis> specification(Filtre filtre) {
		PerimetreService.Perimetre perimetre = perimetre();
		return (racine, requete, cb) -> {
			List<Predicate> criteres = new ArrayList<>();
			if (!perimetre.global()) {
				// Hors vue consolidée : seulement les emplacements attribués (les motos en cours d'importation n'en ont pas).
				criteres.add(perimetre.emplacementIds().isEmpty() ? cb.disjunction() : racine.get("emplacement").get("id").in(perimetre.emplacementIds()));
			}
			if (filtre.recherche() != null && !filtre.recherche().isBlank()) {
				String q = "%" + NumeroChassis.normaliser(filtre.recherche()) + "%";
				List<Long> ids = jdbc.sql("SELECT DISTINCT chassis_id FROM corrections_numero WHERE statut = 'VALIDEE' AND ancien_numero LIKE :q")
						.param("q", q).query(Long.class).list();
				Predicate parNumero = cb.or(cb.like(racine.get("numero"), q), cb.like(cb.upper(cb.coalesce(racine.get("numeroMoteur"), "")), q));
				criteres.add(ids.isEmpty() ? parNumero : cb.or(parNumero, racine.get("id").in(ids)));
			}
			if (filtre.statut() != null) {
				criteres.add(cb.equal(racine.get("statut"), filtre.statut()));
			}
			if (filtre.modeleId() != null) {
				criteres.add(cb.equal(racine.get("modele").get("id"), filtre.modeleId()));
			}
			if (filtre.emplacementId() != null) {
				criteres.add(cb.equal(racine.get("emplacement").get("id"), filtre.emplacementId()));
			}
			if (filtre.arrivageId() != null) {
				criteres.add(cb.equal(racine.get("arrivageId"), filtre.arrivageId()));
			}
			if (filtre.ancienneteMin() != null) {
				criteres.add(cb.lessThanOrEqualTo(racine.get("dateEntree"), LocalDate.now(horloge).minusDays(filtre.ancienneteMin())));
			}
			return cb.and(criteres.toArray(new Predicate[0]));
		};
	}

	private boolean dansLePerimetre(Chassis c) {
		PerimetreService.Perimetre p = perimetre();
		return p.global() || (c.getEmplacement() != null && p.autorise(c.getEmplacement().getId()));
	}

	private PerimetreService.Perimetre perimetre() {
		Long utilisateur = ActeurAuthentifie.courant().map(ActeurAuthentifie::id)
				.orElseThrow(() -> new ErreurMetierException("NON_AUTHENTIFIE", "Connexion requise.", HttpStatus.UNAUTHORIZED));
		return perimetres.perimetreDe(utilisateur);
	}

	private ChassisDto versDto(Chassis c) {
		boolean cout = Droits.aPermission("prix_achat.voir");
		Long anciennete = c.getDateEntree() == null ? null : ChronoUnit.DAYS.between(c.getDateEntree(), LocalDate.now(horloge));
		var m = c.getModele();
		String libelleModele = m.getMarque() + " " + m.getNom() + (m.getVersionModele() == null ? "" : " " + m.getVersionModele());
		return new ChassisDto(c.getId(), c.getNumero(), m.getId(), libelleModele, m.getCodeArticle(), c.getStatut(), c.getStatut().libelle(),
				EmplacementRef.de(c.getEmplacement()), c.getDateEntree(), anciennete, cout ? c.getCoutRevient() : null, cout, c.getCouleur(),
				c.getNumeroMoteur(), c.getArrivageId());
	}

	private static String cellule(String valeur) {
		if (valeur == null) {
			return "";
		}
		// Une cellule qui commence par = + - @ serait lue comme une formule par un tableur : on la neutralise.
		String v = valeur.matches("^[=+\\-@].*") ? "'" + valeur : valeur;
		return v.contains(";") || v.contains("\"") || v.contains("\n") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
	}

}
