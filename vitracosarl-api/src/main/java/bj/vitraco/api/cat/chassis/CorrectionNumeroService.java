package bj.vitraco.api.cat.chassis;

import bj.vitraco.api.adm.utilisateurs.UtilisateurRepository;
import bj.vitraco.api.aud.EntreeAudit;
import bj.vitraco.api.aud.JournalAudit;
import bj.vitraco.api.common.CodeModule;
import bj.vitraco.api.common.ErreurMetierException;
import bj.vitraco.api.common.ErreurValidationException;
import bj.vitraco.api.common.security.ActeurAuthentifie;
import bj.vitraco.api.notif.DemandeNotification;
import bj.vitraco.api.notif.Notifications;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * « Corriger avec autorisation » (EF-CAT-07, US-013) : un numéro mal saisi ne se modifie pas à la main. Un utilisateur
 * demande la correction avec un motif, un second la valide (RG-19). L'ancien numéro reste conservé et recherchable.
 */
@Service
public class CorrectionNumeroService {

	public record Correction(Long id, Long chassisId, String ancienNumero, String nouveauNumero, String motif, String statut,
							 String demandePar, java.time.Instant demandeLe, String decidePar, java.time.Instant decideLe, String motifRefus) {
	}

	private final ChassisRepository chassis;
	private final UtilisateurRepository utilisateurs;
	private final ChassisService service;
	private final MouvementsStock mouvements;
	private final List<VerificationCorrectionChassis> verifications;
	private final JdbcClient jdbc;
	private final JournalAudit journal;
	private final Notifications notifications;

	CorrectionNumeroService(ChassisRepository chassis, UtilisateurRepository utilisateurs, ChassisService service, MouvementsStock mouvements,
							List<VerificationCorrectionChassis> verifications, JdbcClient jdbc, JournalAudit journal, Notifications notifications) {
		this.chassis = chassis;
		this.utilisateurs = utilisateurs;
		this.service = service;
		this.mouvements = mouvements;
		this.verifications = verifications;
		this.jdbc = jdbc;
		this.journal = journal;
		this.notifications = notifications;
	}

	@Transactional
	public Correction demander(Long chassisId, String nouveauNumeroSaisi, String motif) {
		Chassis c = chassis.findById(chassisId).orElseThrow(ChassisConsultation::introuvable);
		if (motif == null || motif.isBlank()) {
			throw new ErreurValidationException("motif", "Le motif de la correction est obligatoire.");
		}
		String nouveau = verifierNouveauNumero(c, nouveauNumeroSaisi);
		if (jdbc.sql("SELECT count(*) FROM corrections_numero WHERE chassis_id = :c AND statut = 'DEMANDEE'").param("c", chassisId)
				.query(Long.class).single() > 0) {
			throw new ErreurMetierException("CORRECTION_DEJA_DEMANDEE", "Une correction est déjà en attente de validation pour ce châssis.",
					HttpStatus.UNPROCESSABLE_ENTITY);
		}
		refuserSiVerrouille(c);

		Long auteur = acteurId();
		Long id = jdbc.sql("""
						INSERT INTO corrections_numero (chassis_id, ancien_numero, nouveau_numero, motif, demande_par)
						VALUES (:c, :a, :n, :m, :u) RETURNING id""")
				.param("c", chassisId).param("a", c.getNumero()).param("n", nouveau).param("m", motif.trim()).param("u", auteur)
				.query(Long.class).single();
		journal.enregistrer(EntreeAudit.de(CodeModule.CAT, "chassis.correction_demandee", "Chassis", String.valueOf(chassisId))
				.avecValeurs(Map.of("numero", c.getNumero()), Map.of("numero", nouveau)).avecMotif(motif.trim()));
		// Les magasiniers voient la demande dans leur cloche et ouvrent la fiche, où se valide la correction.
		notifications.notifierRole("magasin", DemandeNotification.de("chassis.correction_demandee", "Correction de châssis à valider",
				auteurLibelle() + " demande de corriger le châssis " + c.getNumero() + " en " + nouveau + ".\nMotif : " + motif.trim(),
				"/catalogue/chassis/" + chassisId));
		return correction(id);
	}

	@Transactional
	public Correction valider(Long correctionId) {
		Correction demande = correctionEnAttente(correctionId);
		Chassis c = chassis.findById(demande.chassisId()).orElseThrow(ChassisConsultation::introuvable);
		exigerAutreUtilisateur(correctionId);

		// Les contrôles sont refaits : entre la demande et la validation, le numéro a pu être pris ou la moto facturée.
		verifierNouveauNumero(c, demande.nouveauNumero());
		refuserSiVerrouille(c);

		String ancien = c.getNumero();
		c.setNumero(demande.nouveauNumero());
		c.setModifiePar(utilisateurs.findById(acteurId()).orElse(null));
		try {
			chassis.saveAndFlush(c);
		} catch (DataIntegrityViolationException e) {
			throw new ErreurValidationException("nouveau_numero", "Ce numéro vient d'être pris par un autre châssis.");
		}
		jdbc.sql("UPDATE corrections_numero SET statut = 'VALIDEE', decide_par = :u, decide_le = now() WHERE id = :id")
				.param("u", acteurId()).param("id", correctionId).update();
		mouvements.enregistrer(c.getId(), "CORRECTION_NUMERO", null, null, c.getStatut(), c.getStatut(), "CORRECTION_NUMERO", correctionId,
				"Numéro corrigé de " + ancien + " en " + c.getNumero() + " — " + demande.motif());
		journal.enregistrer(EntreeAudit.de(CodeModule.CAT, "chassis.correction_validee", "Chassis", String.valueOf(c.getId()))
				.avecValeurs(Map.of("numero", ancien), Map.of("numero", c.getNumero())).avecMotif(demande.motif()));
		notifierDemandeur(demande, "Correction de châssis validée",
				"Le châssis " + ancien + " est désormais " + c.getNumero() + ". L'ancien numéro reste recherchable.", c.getId());
		return correction(correctionId);
	}

	@Transactional
	public Correction refuser(Long correctionId, String motifRefus) {
		Correction demande = correctionEnAttente(correctionId);
		exigerAutreUtilisateur(correctionId);
		if (motifRefus == null || motifRefus.isBlank()) {
			throw new ErreurValidationException("motif", "Le motif du refus est obligatoire.");
		}
		jdbc.sql("UPDATE corrections_numero SET statut = 'REFUSEE', decide_par = :u, decide_le = now(), motif_refus = :m WHERE id = :id")
				.param("u", acteurId()).param("m", motifRefus.trim()).param("id", correctionId).update();
		journal.enregistrer(EntreeAudit.de(CodeModule.CAT, "chassis.correction_refusee", "Chassis", String.valueOf(demande.chassisId()))
				.avecValeurs(Map.of("numero", demande.ancienNumero()), Map.of("numero_demande", demande.nouveauNumero())).avecMotif(motifRefus.trim()));
		notifierDemandeur(demande, "Correction de châssis refusée",
				"La correction du châssis " + demande.ancienNumero() + " en " + demande.nouveauNumero() + " est refusée.\nMotif : " + motifRefus.trim(),
				demande.chassisId());
		return correction(correctionId);
	}

	@Transactional(readOnly = true)
	public List<Correction> deLaFiche(Long chassisId) {
		return jdbc.sql(selection() + " WHERE c.chassis_id = :c ORDER BY c.demande_le DESC, c.id DESC").param("c", chassisId)
				.query((rs, n) -> versCorrection(rs)).list();
	}

	/** Contrôles communs à la demande et à la validation : forme, format du modèle, unicité. Renvoie le numéro normalisé. */
	private String verifierNouveauNumero(Chassis c, String saisie) {
		String nouveau = NumeroChassis.normaliser(saisie);
		if (!NumeroChassis.valide(nouveau)) {
			throw new ErreurValidationException("nouveau_numero", "Numéro de châssis invalide : 3 à 40 lettres ou chiffres (espaces et tirets ignorés).");
		}
		if (nouveau.equals(c.getNumero())) {
			throw new ErreurValidationException("nouveau_numero", "C'est déjà le numéro de ce châssis.");
		}
		ChassisService.verifierFormat(c.getModele(), nouveau);
		service.verifierPasDeDoublon(nouveau);
		return nouveau;
	}

	private void refuserSiVerrouille(Chassis c) {
		for (VerificationCorrectionChassis v : verifications) {
			v.refus(c.getId()).ifPresent(motif -> {
				throw new ErreurMetierException("CORRECTION_IMPOSSIBLE", "Ce numéro ne peut plus être corrigé : " + motif, HttpStatus.UNPROCESSABLE_ENTITY);
			});
		}
	}

	private void exigerAutreUtilisateur(Long correctionId) {
		Long demandeur = jdbc.sql("SELECT demande_par FROM corrections_numero WHERE id = :id").param("id", correctionId).query(Long.class).single();
		if (demandeur.equals(acteurId())) {
			throw new ErreurMetierException("UTILISATEUR_IDENTIQUE",
					"Une correction de châssis doit être décidée par un autre utilisateur que celui qui l'a demandée.", HttpStatus.FORBIDDEN);
		}
	}

	private Correction correctionEnAttente(Long id) {
		Correction c = correction(id);
		if (!"DEMANDEE".equals(c.statut())) {
			throw new ErreurMetierException("CORRECTION_DEJA_TRAITEE", "Cette correction a déjà été traitée.", HttpStatus.UNPROCESSABLE_ENTITY);
		}
		return c;
	}

	private Correction correction(Long id) {
		return jdbc.sql(selection() + " WHERE c.id = :id").param("id", id).query((rs, n) -> versCorrection(rs)).optional()
				.orElseThrow(() -> new ErreurMetierException("CORRECTION_INTROUVABLE", "Correction introuvable.", HttpStatus.NOT_FOUND));
	}

	private void notifierDemandeur(Correction demande, String titre, String message, Long chassisId) {
		Long demandeur = jdbc.sql("SELECT demande_par FROM corrections_numero WHERE id = :id").param("id", demande.id()).query(Long.class).single();
		notifications.notifierUtilisateur(demandeur, DemandeNotification.de("chassis.correction_decidee", titre, message, "/catalogue/chassis/" + chassisId));
	}

	private static String selection() {
		return """
				SELECT c.id, c.chassis_id, c.ancien_numero, c.nouveau_numero, c.motif, c.statut,
				       trim(coalesce(d.prenom || ' ', '') || d.nom) AS demandeur, c.demande_le,
				       trim(coalesce(v.prenom || ' ', '') || coalesce(v.nom, '')) AS decideur, c.decide_le, c.motif_refus
				FROM corrections_numero c
				JOIN utilisateurs d ON d.id = c.demande_par
				LEFT JOIN utilisateurs v ON v.id = c.decide_par""";
	}

	private static Correction versCorrection(java.sql.ResultSet rs) throws java.sql.SQLException {
		String decideur = rs.getString("decideur");
		return new Correction(rs.getLong("id"), rs.getLong("chassis_id"), rs.getString("ancien_numero"), rs.getString("nouveau_numero"),
				rs.getString("motif"), rs.getString("statut"), rs.getString("demandeur"), rs.getTimestamp("demande_le").toInstant(),
				decideur == null || decideur.isBlank() ? null : decideur,
				rs.getTimestamp("decide_le") == null ? null : rs.getTimestamp("decide_le").toInstant(), rs.getString("motif_refus"));
	}

	private static Long acteurId() {
		return ActeurAuthentifie.courant().map(ActeurAuthentifie::id)
				.orElseThrow(() -> new ErreurMetierException("NON_AUTHENTIFIE", "Connexion requise.", HttpStatus.UNAUTHORIZED));
	}

	private static String auteurLibelle() {
		return ActeurAuthentifie.courant().map(ActeurAuthentifie::libelle).orElse("Un utilisateur");
	}

}
