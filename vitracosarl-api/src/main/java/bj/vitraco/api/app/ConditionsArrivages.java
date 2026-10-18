package bj.vitraco.api.app;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Les arrivages commandent les étapes de la commande qui les concernent (SRS 5.3) : on ne passe « en transit » qu'avec
 * un arrivage, « au port » qu'une fois la marchandise arrivée, etc.
 */
@Component
class ConditionsArrivages implements ConditionEtapeCommande {

	private final ArrivageRepository arrivages;

	ConditionsArrivages(ArrivageRepository arrivages) {
		this.arrivages = arrivages;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<String> refus(Long commandeId, StatutCommande cible) {
		List<Arrivage> liste = arrivages.findByCommandeIdOrderById(commandeId);
		return switch (cible) {
			case EN_TRANSIT -> exiger(liste, a -> true, "aucun arrivage n'est enregistré (B/L, conteneur, dates).");
			case AU_PORT -> exiger(liste, a -> a.getDateArriveeReelle() != null, "aucune date d'arrivée réelle n'est saisie sur les arrivages.");
			case DEDOUANEMENT -> exiger(liste, a -> a.getStatutDouane() != StatutDouane.NON_DEMARREE, "le dédouanement n'est démarré sur aucun arrivage.");
			case A_RECEPTIONNER -> exiger(liste, a -> a.getStatutDouane() == StatutDouane.TERMINEE, "le dédouanement n'est terminé sur aucun arrivage.");
			default -> Optional.empty();
		};
	}

	private static Optional<String> exiger(List<Arrivage> liste, Predicate<Arrivage> condition, String motif) {
		return liste.stream().anyMatch(condition) ? Optional.empty() : Optional.of(motif);
	}

}
