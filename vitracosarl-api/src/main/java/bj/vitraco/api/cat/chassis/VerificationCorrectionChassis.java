package bj.vitraco.api.cat.chassis;

import java.util.Optional;

/**
 * Point d'extension : un module qui rattache le châssis à un document verrouillé dit pourquoi son numéro ne peut plus
 * changer. La facturation y déclarera les factures normalisées (US-013) ; sans implémentation, rien ne s'oppose à la correction.
 */
public interface VerificationCorrectionChassis {

	/** Motif du refus, ou vide si ce module ne s'oppose pas à la correction. */
	Optional<String> refus(Long chassisId);

}
