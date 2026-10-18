package bj.vitraco.api.adm.emplacements;

/**
 * Point d'extension : un module qui porte des données par emplacement (stock, opérations en
 * cours…) déclare ici si l'emplacement est encore utilisé. Tant que cela est vrai, on ne peut
 * ni changer le mode d'une boutique (EF-ADM-09) ni désactiver l'emplacement (EF-ADM-08).
 * Implémentation réelle : {@code UsageEmplacementStock} (module STK) ; les tests peuvent en ajouter une simulée.
 */
public interface VerificationUsageEmplacement {

	boolean aStockOuOperationEnCours(Long emplacementId);

}
