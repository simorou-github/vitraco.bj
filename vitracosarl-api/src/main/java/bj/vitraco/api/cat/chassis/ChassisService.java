package bj.vitraco.api.cat.chassis;

import bj.vitraco.api.adm.emplacements.Emplacement;
import bj.vitraco.api.adm.emplacements.EmplacementRepository;
import bj.vitraco.api.adm.utilisateurs.UtilisateurRepository;
import bj.vitraco.api.aud.EntreeAudit;
import bj.vitraco.api.aud.JournalAudit;
import bj.vitraco.api.cat.Modele;
import bj.vitraco.api.cat.ModeleRepository;
import bj.vitraco.api.common.CodeModule;
import bj.vitraco.api.common.ErreurMetierException;
import bj.vitraco.api.common.ErreurValidationException;
import bj.vitraco.api.common.security.ActeurAuthentifie;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Point d'entrée des autres modules pour créer un châssis. Il contrôle le numéro (forme, format du modèle, unicité) et
 * écrit le premier mouvement dans la même transaction. Les transitions suivantes (réception, réservation, livraison…)
 * s'ajouteront ici avec leurs modules : aucun autre code ne doit modifier un statut ou un emplacement.
 */
@Service
public class ChassisService {

	private final ChassisRepository chassis;
	private final ModeleRepository modeles;
	private final EmplacementRepository emplacements;
	private final UtilisateurRepository utilisateurs;
	private final MouvementsStock mouvements;
	private final JournalAudit journal;
	private final Clock horloge;

	ChassisService(ChassisRepository chassis, ModeleRepository modeles, EmplacementRepository emplacements, UtilisateurRepository utilisateurs,
				   MouvementsStock mouvements, JournalAudit journal, Clock horloge) {
		this.chassis = chassis;
		this.modeles = modeles;
		this.emplacements = emplacements;
		this.utilisateurs = utilisateurs;
		this.mouvements = mouvements;
		this.journal = journal;
		this.horloge = horloge;
	}

	@Transactional(readOnly = true)
	public Optional<Chassis> parNumero(String numeroSaisi) {
		return chassis.findByNumero(NumeroChassis.normaliser(numeroSaisi));
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public Chassis creer(CreationChassis demande) {
		return creer(demande, null);
	}

	/** Comme {@link #creer(CreationChassis)}, avec la date d'entrée en stock d'une moto déjà en magasin (reprise du stock initial). */
	@Transactional(propagation = Propagation.MANDATORY)
	public Chassis creer(CreationChassis demande, LocalDate dateEntree) {
		String numero = NumeroChassis.normaliser(demande.numeroSaisi());
		if (!NumeroChassis.valide(numero)) {
			throw new ErreurValidationException("numero", "Numéro de châssis invalide : 3 à 40 lettres ou chiffres (espaces et tirets ignorés).");
		}
		Modele modele = modeles.findById(demande.modeleId())
				.orElseThrow(() -> new ErreurValidationException("modele_id", "Modèle inconnu."));
		verifierFormat(modele, numero);
		verifierPasDeDoublon(numero);

		StatutChassis statut = demande.statutInitial();
		if (statut != StatutChassis.EN_TRANSIT_FOURNISSEUR && statut != StatutChassis.DISPONIBLE && statut != StatutChassis.BLOQUEE) {
			throw new ErreurMetierException("STATUT_INITIAL_INVALIDE",
					"Un châssis se crée en transit fournisseur (import du colisage), disponible (réception non annoncée) ou bloqué (réception d'une moto endommagée).");
		}
		Emplacement emplacement = null;
		if (statut.enCoursImportation()) {
			if (demande.emplacementId() != null) {
				throw new ErreurMetierException("EMPLACEMENT_INTERDIT", "Un châssis en cours d'importation n'a pas encore d'emplacement.");
			}
		} else {
			emplacement = emplacements.findById(demande.emplacementId() == null ? -1L : demande.emplacementId())
					.filter(Emplacement::isActif)
					.orElseThrow(() -> new ErreurValidationException("emplacement_id", "Emplacement inconnu ou désactivé."));
		}

		Chassis c = new Chassis(numero, modele, statut, emplacement);
		c.setArrivageId(demande.arrivageId());
		c.setReceptionId(demande.receptionId());
		c.setCoutRevient(demande.coutRevient());
		c.setNumeroMoteur(vide(demande.numeroMoteur()));
		c.setCouleur(vide(demande.couleur()));
		c.setDateEntree(statut.enCoursImportation() ? null : dateEntree != null ? dateEntree : LocalDate.now(horloge));
		ActeurAuthentifie.courant().flatMap(a -> utilisateurs.findById(a.id())).ifPresent(c::setCreePar);
		try {
			chassis.saveAndFlush(c);
		} catch (DataIntegrityViolationException e) {
			// Deux saisies simultanées du même numéro : l'index unique tranche, l'utilisateur voit le même message qu'un doublon.
			throw doublon(chassis.findByNumero(numero));
		}

		mouvements.enregistrer(c.getId(), "CREATION", null, emplacement == null ? null : emplacement.getId(), null, statut,
				demande.documentType(), demande.documentId(), demande.motif());
		Map<String, Object> apres = new LinkedHashMap<>();
		apres.put("numero", numero);
		apres.put("modele", modele.getCodeArticle());
		apres.put("statut", statut);
		apres.put("emplacement", emplacement == null ? null : emplacement.getCode());
		journal.enregistrer(EntreeAudit.de(CodeModule.CAT, "chassis.cree", "Chassis", String.valueOf(c.getId())).avecValeurs(null, apres));
		return c;
	}

	/**
	 * Le châssis pourrait-il être créé ? Même contrôles que {@link #creer}, mais le motif du refus est renvoyé au lieu
	 * d'être levé : un import de colisage rejette ses mauvaises lignes sans annuler les bonnes.
	 */
	@Transactional(readOnly = true)
	public Optional<String> controler(String numeroSaisi, Long modeleId) {
		String numero = NumeroChassis.normaliser(numeroSaisi);
		if (!NumeroChassis.valide(numero)) {
			return Optional.of("numéro invalide : 3 à 40 lettres ou chiffres (espaces et tirets ignorés)");
		}
		Optional<Modele> modele = modeles.findById(modeleId);
		if (modele.isEmpty()) {
			return Optional.of("modèle inconnu");
		}
		String format = modele.get().getFormatChassis();
		if (format != null && !format.isBlank()) {
			try {
				if (!Pattern.compile(format).matcher(numero).matches()) {
					return Optional.of("le numéro " + numero + " ne respecte pas le format du modèle " + modele.get().getCodeArticle() + " (" + format + ")");
				}
			} catch (PatternSyntaxException e) {
				return Optional.of("le format de châssis du modèle " + modele.get().getCodeArticle() + " est invalide");
			}
		}
		return chassis.findByNumero(numero).map(c -> "châssis déjà enregistré : " + c.getStatut().libelle()
				+ (c.getEmplacement() == null ? "" : " à " + c.getEmplacement().getNom()));
	}

	/**
	 * Entrée en stock d'une moto annoncée, à la validation d'une réception : elle quitte « à réceptionner » pour le magasin,
	 * « disponible », ou « bloquée » si elle est arrivée endommagée (EF-REC-07). Seule porte d'entrée de ce changement.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Chassis receptionner(Long chassisId, Long emplacementId, boolean endommage, String motif, Long receptionId) {
		Chassis c = chassis.findById(chassisId).orElseThrow(() -> new ErreurMetierException("CHASSIS_INTROUVABLE", "Châssis introuvable.", HttpStatus.NOT_FOUND));
		if (c.getStatut() != StatutChassis.A_RECEPTIONNER) {
			throw new ErreurMetierException("CHASSIS_NON_RECEPTIONNABLE",
					"Le châssis " + c.getNumero() + " n'est pas à réceptionner (statut « " + c.getStatut().libelle() + " »).");
		}
		Emplacement destination = emplacements.findById(emplacementId)
				.filter(Emplacement::isActif).orElseThrow(() -> new ErreurValidationException("emplacement_id", "Emplacement inconnu ou désactivé."));
		StatutChassis avant = c.getStatut();
		StatutChassis apres = endommage ? StatutChassis.BLOQUEE : StatutChassis.DISPONIBLE;
		c.setStatut(apres);
		c.setEmplacement(destination);
		c.setDateEntree(LocalDate.now(horloge));
		c.setReceptionId(receptionId);
		ActeurAuthentifie.courant().flatMap(a -> utilisateurs.findById(a.id())).ifPresent(c::setModifiePar);
		chassis.saveAndFlush(c);
		mouvements.enregistrer(c.getId(), "RECEPTION", null, destination.getId(), avant, apres, "RECEPTION", receptionId, motif);
		journal.enregistrer(EntreeAudit.de(CodeModule.CAT, "chassis.receptionne", "Chassis", String.valueOf(c.getId()))
				.avecValeurs(Map.of("statut", avant), Map.of("statut", apres, "emplacement", destination.getCode())).avecMotif(motif));
		return c;
	}

	/**
	 * Le dédouanement d'un arrivage est terminé : ses motos annoncées passent de « en transit fournisseur » à « à
	 * réceptionner » (SRS 3.4). Elles restent hors stock jusqu'à la validation de leur réception.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public int marquerAReceptionner(Long arrivageId) {
		var concernes = chassis.findByArrivageIdAndStatut(arrivageId, StatutChassis.EN_TRANSIT_FOURNISSEUR);
		for (Chassis c : concernes) {
			c.setStatut(StatutChassis.A_RECEPTIONNER);
			chassis.save(c);
			chassis.flush();
			mouvements.enregistrer(c.getId(), "ARRIVAGE_A_RECEPTIONNER", null, null, StatutChassis.EN_TRANSIT_FOURNISSEUR, StatutChassis.A_RECEPTIONNER,
					"ARRIVAGE", arrivageId, "Dédouanement terminé");
		}
		if (!concernes.isEmpty()) {
			journal.enregistrer(EntreeAudit.de(CodeModule.CAT, "chassis.a_receptionner", "Arrivage", String.valueOf(arrivageId))
					.avecValeurs(Map.of("statut", StatutChassis.EN_TRANSIT_FOURNISSEUR), Map.of("statut", StatutChassis.A_RECEPTIONNER, "nombre", concernes.size())));
		}
		return concernes.size();
	}

	/**
	 * Bloque une moto en litige (EF-STK-06) : elle reste dans son emplacement, comptée au stock physique, mais n'est plus
	 * proposée à la vente. Seule une moto disponible se bloque : une moto réservée doit d'abord quitter sa commande.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Chassis bloquer(Long chassisId, String motif, String piece) {
		Chassis c = trouver(chassisId);
		if (c.getStatut() != StatutChassis.DISPONIBLE) {
			throw new ErreurMetierException("CHASSIS_NON_BLOCABLE", "Seule une moto disponible peut être bloquée : le châssis " + c.getNumero()
					+ " est « " + c.getStatut().libelle() + " ».");
		}
		return changerDeStatutSurPlace(c, StatutChassis.BLOQUEE, "BLOCAGE", "chassis.bloque", motif + " (pièce : " + piece + ")", motif, piece);
	}

	/** Remet en vente une moto bloquée, avec un motif tracé. */
	@Transactional(propagation = Propagation.MANDATORY)
	public Chassis debloquer(Long chassisId, String motif) {
		Chassis c = trouver(chassisId);
		if (c.getStatut() != StatutChassis.BLOQUEE) {
			throw new ErreurMetierException("CHASSIS_NON_BLOQUE", "Le châssis " + c.getNumero() + " n'est pas bloqué (« " + c.getStatut().libelle() + " »).");
		}
		return changerDeStatutSurPlace(c, StatutChassis.DISPONIBLE, "DEBLOCAGE", "chassis.debloque", motif, motif, null);
	}

	private Chassis changerDeStatutSurPlace(Chassis c, StatutChassis apres, String type, String action, String motifMouvement, String motif, String piece) {
		StatutChassis avant = c.getStatut();
		Long lieu = c.getEmplacement() == null ? null : c.getEmplacement().getId();
		c.setStatut(apres);
		ActeurAuthentifie.courant().flatMap(a -> utilisateurs.findById(a.id())).ifPresent(c::setModifiePar);
		chassis.saveAndFlush(c);
		mouvements.enregistrer(c.getId(), type, lieu, lieu, avant, apres, type, null, motifMouvement);
		Map<String, Object> apresValeurs = new LinkedHashMap<>();
		apresValeurs.put("statut", apres);
		if (piece != null) {
			apresValeurs.put("piece", piece);
		}
		journal.enregistrer(EntreeAudit.de(CodeModule.CAT, action, "Chassis", String.valueOf(c.getId()))
				.avecValeurs(Map.of("statut", avant), apresValeurs).avecMotif(motif));
		return c;
	}

	private Chassis trouver(Long chassisId) {
		return chassis.findById(chassisId).orElseThrow(() -> new ErreurMetierException("CHASSIS_INTROUVABLE", "Châssis introuvable.", HttpStatus.NOT_FOUND));
	}

	/**
	 * Expédition d'un transfert (SRS 3.4) : la moto quitte son emplacement et passe « en transit interne », dans
	 * l'emplacement virtuel de transit, où elle n'est comptée ni à la source ni à la destination.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Chassis expedier(Long chassisId, Long sourceId, Long transitId, Long transfertId) {
		Chassis c = trouver(chassisId);
		if (c.getStatut() != StatutChassis.DISPONIBLE || c.getEmplacement() == null || !c.getEmplacement().getId().equals(sourceId)) {
			throw new ErreurMetierException("CHASSIS_NON_TRANSFERABLE", "Le châssis " + c.getNumero() + " n'est plus disponible à la source (« "
					+ c.getStatut().libelle() + " »" + (c.getEmplacement() == null ? "" : " à " + c.getEmplacement().getNom()) + ").");
		}
		return deplacer(c, StatutChassis.EN_TRANSIT_INTERNE, transitId, "TRANSFERT_EXPEDITION", transfertId, "chassis.expedie");
	}

	/** Réception d'un transfert : la moto entre à la destination et retrouve son statut d'avant l'expédition. */
	@Transactional(propagation = Propagation.MANDATORY)
	public Chassis receptionnerTransfert(Long chassisId, Long destinationId, StatutChassis statutRetour, Long transfertId) {
		Chassis c = trouver(chassisId);
		if (c.getStatut() != StatutChassis.EN_TRANSIT_INTERNE) {
			throw new ErreurMetierException("CHASSIS_NON_EN_TRANSIT", "Le châssis " + c.getNumero() + " n'est pas en transit interne (« " + c.getStatut().libelle() + " »).");
		}
		return deplacer(c, statutRetour, destinationId, "TRANSFERT_RECEPTION", transfertId, "chassis.receptionne_transfert");
	}

	private Chassis deplacer(Chassis c, StatutChassis apres, Long destinationId, String type, Long transfertId, String action) {
		Emplacement destination = emplacements.findById(destinationId)
				.orElseThrow(() -> new ErreurValidationException("emplacement_id", "Emplacement inconnu."));
		StatutChassis avant = c.getStatut();
		Long source = c.getEmplacement() == null ? null : c.getEmplacement().getId();
		c.setStatut(apres);
		c.setEmplacement(destination);
		ActeurAuthentifie.courant().flatMap(a -> utilisateurs.findById(a.id())).ifPresent(c::setModifiePar);
		chassis.saveAndFlush(c);
		mouvements.enregistrer(c.getId(), type, source, destination.getId(), avant, apres, "TRANSFERT", transfertId, null);
		journal.enregistrer(EntreeAudit.de(CodeModule.CAT, action, "Chassis", String.valueOf(c.getId()))
				.avecValeurs(Map.of("statut", avant), Map.of("statut", apres, "emplacement", destination.getCode())));
		return c;
	}

	/** Le numéro respecte-t-il le format du modèle ? Sans format défini, seule l'unicité est contrôlée. */
	static void verifierFormat(Modele modele, String numero) {
		String format = modele.getFormatChassis();
		if (format == null || format.isBlank()) {
			return;
		}
		try {
			if (!Pattern.compile(format).matcher(numero).matches()) {
				throw new ErreurValidationException("numero",
						"Le numéro " + numero + " ne respecte pas le format du modèle " + modele.getCodeArticle() + " (" + format + ").");
			}
		} catch (PatternSyntaxException e) {
			throw new ErreurMetierException("FORMAT_MODELE_INVALIDE", "Le format de châssis du modèle " + modele.getCodeArticle() + " est invalide.");
		}
	}

	void verifierPasDeDoublon(String numero) {
		Optional<Chassis> existant = chassis.findByNumero(numero);
		if (existant.isPresent()) {
			throw doublon(existant);
		}
	}

	private static ErreurMetierException doublon(Optional<Chassis> existant) {
		String ou = existant.map(c -> c.getStatut().libelle() + (c.getEmplacement() == null ? "" : " à " + c.getEmplacement().getNom())).orElse("déjà enregistré");
		return new ErreurMetierException("CHASSIS_DEJA_EXISTANT", "Ce châssis existe déjà : " + ou + ".", HttpStatus.UNPROCESSABLE_ENTITY);
	}

	private static String vide(String valeur) {
		return valeur == null || valeur.isBlank() ? null : valeur.trim();
	}

}
