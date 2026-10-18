package bj.vitraco.api.adm.droits;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Permissions et rôles livrés par défaut (SRS §9.1 et §9.2). La matrice est un point de
 * départ que l'administrateur peut ajuster (EF-ADM-03) : elle n'est appliquée qu'à la
 * création d'un rôle, jamais pour écraser un réglage existant.
 */
public final class CatalogueDroits {

	public record DefinitionPermission(String code, String libelle, String module, String action) {
	}

	public record DefinitionRole(String code, String libelle, String description, boolean perimetreGlobal, boolean doubleAuthentificationRequise, Set<String> permissions) {
	}

	private record Module(String cle, String libelle) {
	}

	private enum ActionStandard {
		VOIR("voir", "Voir"),
		CREER("creer", "Créer"),
		MODIFIER("modifier", "Modifier"),
		VALIDER("valider", "Valider"),
		ANNULER("annuler", "Annuler"),
		EXPORTER("exporter", "Exporter");

		final String code;
		final String libelle;

		ActionStandard(String code, String libelle) {
			this.code = code;
			this.libelle = libelle;
		}
	}

	/** Colonnes de la matrice, dans l'ordre du SRS §9.1. */
	private static final List<String> ROLES = List.of(
			"admin", "direction", "responsable_commercial", "commercial",
			"comptabilite_caisse", "magasin", "controleur", "boutique");

	/**
	 * Niveau par rôle : T paramétrage complet, C créer et modifier, V valider, CV les deux,
	 * L lecture, - aucun accès.
	 */
	private static final Map<Module, String[]> MATRICE = new LinkedHashMap<>();

	static {
		MATRICE.put(new Module("administration", "Administration"), new String[]{"T", "L", "-", "-", "-", "-", "-", "-"});
		MATRICE.put(new Module("catalogue", "Catalogue et prix"), new String[]{"T", "V", "C", "L", "L", "L", "L", "L"});
		MATRICE.put(new Module("approvisionnements", "Approvisionnements"), new String[]{"C", "V", "-", "-", "C", "L", "L", "-"});
		MATRICE.put(new Module("receptions", "Réceptions"), new String[]{"L", "L", "-", "-", "L", "CV", "L", "-"});
		MATRICE.put(new Module("cout_revient", "Coût de revient"), new String[]{"L", "L", "-", "-", "CV", "-", "L", "-"});
		MATRICE.put(new Module("stock", "Stock"), new String[]{"L", "L", "L", "L", "L", "L", "L", "L"});
		MATRICE.put(new Module("transferts", "Transferts"), new String[]{"L", "L", "-", "-", "-", "CV", "L", "C"});
		MATRICE.put(new Module("clients", "Clients et revendeurs"), new String[]{"L", "V", "CV", "C", "L", "-", "L", "C"});
		MATRICE.put(new Module("commandes", "Commandes"), new String[]{"L", "V", "CV", "C", "L", "L", "L", "C"});
		MATRICE.put(new Module("livraisons", "Livraisons"), new String[]{"L", "V", "L", "L", "L", "CV", "L", "C"});
		MATRICE.put(new Module("factures", "Factures et avoirs"), new String[]{"L", "V", "L", "L", "CV", "-", "L", "C"});
		MATRICE.put(new Module("reglements", "Règlements"), new String[]{"L", "L", "L", "C", "CV", "-", "L", "C"});
		MATRICE.put(new Module("creances", "Créances"), new String[]{"L", "L", "L", "L", "C", "-", "L", "-"});
		MATRICE.put(new Module("depot_vente", "Dépôt-vente"), new String[]{"L", "V", "CV", "C", "L", "C", "L", "-"});
		MATRICE.put(new Module("inventaires", "Inventaires"), new String[]{"L", "V", "-", "-", "L", "C", "C", "C"});
		MATRICE.put(new Module("ajustements", "Ajustements"), new String[]{"L", "V", "-", "-", "-", "C", "C", "-"});
		MATRICE.put(new Module("caisse_depenses", "Caisse et dépenses"), new String[]{"L", "V", "-", "-", "CV", "-", "L", "C"});
		MATRICE.put(new Module("anomalies", "Anomalies"), new String[]{"L", "V", "-", "-", "L", "-", "CV", "-"});
		MATRICE.put(new Module("journal_audit", "Journal d'audit"), new String[]{"L", "L", "-", "-", "-", "-", "L", "-"});
		MATRICE.put(new Module("rapports", "Rapports"), new String[]{"L", "L", "L", "L", "L", "L", "L", "L"});
	}

	/** Permissions transverses du §9.2, hors module : code, libellé, rôles qui les reçoivent par défaut. */
	private static final List<Object[]> SPECIALES = List.of(
			new Object[]{"prix_achat.voir", "Voir prix d'achat et coût de revient", List.of("direction", "comptabilite_caisse", "controleur")},
			new Object[]{"marges.voir", "Voir marges et analyses de rentabilité", List.of("direction", "responsable_commercial", "comptabilite_caisse")},
			new Object[]{"depenses.voir", "Voir les dépenses et la trésorerie", List.of("direction", "comptabilite_caisse", "controleur")},
			new Object[]{"stock.modifier", "Demander ou valider un ajustement de stock", List.of("direction", "magasin", "controleur")},
			new Object[]{"stock.bloquer", "Bloquer et débloquer un châssis", List.of("direction", "magasin", "controleur")},
			new Object[]{"stock.importer", "Importer le stock initial depuis Excel", List.of("admin", "direction")},
			new Object[]{"approvisionnements.retour_arriere", "Revenir en arrière sur le statut d'une commande fournisseur", List.of("direction")},
			new Object[]{"inventaires.cloturer", "Valider la clôture d'un inventaire", List.of("direction")},
			new Object[]{"anomalies.cloturer", "Clôturer un dossier d'anomalie", List.of("direction", "controleur")});

	/** Rôles qui peuvent télécharger en PDF ou Excel, en plus de l'administrateur (valeur de départ). */
	private static final Set<String> ROLES_EXPORT = Set.of("direction", "comptabilite_caisse");

	/** Rôles qui voient tous les emplacements : l'administrateur et la Direction (vue consolidée, STK-10). */
	private static final Set<String> ROLES_PERIMETRE_GLOBAL = Set.of("admin", "direction");

	/** Rôles qui doivent activer la double authentification (SRS 7.1, EF-ADM-10). */
	private static final Set<String> ROLES_A2F = Set.of("admin", "direction", "comptabilite_caisse");

	private static final Map<String, String> DESCRIPTIONS = Map.of(
			"admin", "Administration : utilisateurs, rôles, référentiels et paramètres",
			"direction", "Vue consolidée, validations et analyses",
			"responsable_commercial", "Prix, remises, validation des commandes et suivi des commerciaux",
			"commercial", "Clients, commandes et suivi des créances de son portefeuille",
			"comptabilite_caisse", "Factures, règlements, AIB, chèques, dépenses et rapprochements",
			"magasin", "Réceptions, châssis, préparation, livraison, transferts et inventaires",
			"controleur", "Inventaires, dépôt-vente, anomalies et vérifications",
			"boutique", "Ventes boutique, Client Divers, caisse et stock de la boutique");

	private static final Map<String, String> LIBELLES = Map.of(
			"admin", "Administrateur",
			"direction", "Direction",
			"responsable_commercial", "Responsable commercial",
			"commercial", "Commercial",
			"comptabilite_caisse", "Comptabilité / Caisse",
			"magasin", "Magasin",
			"controleur", "Contrôleur",
			"boutique", "Boutique");

	private CatalogueDroits() {
	}

	public static List<DefinitionPermission> permissions() {
		List<DefinitionPermission> liste = new ArrayList<>();
		for (Module module : MATRICE.keySet()) {
			for (ActionStandard action : ActionStandard.values()) {
				liste.add(new DefinitionPermission(module.cle + "." + action.code,
						action.libelle + " : " + module.libelle, module.cle, action.code));
			}
		}
		for (Object[] speciale : SPECIALES) {
			String code = (String) speciale[0];
			String[] morceaux = code.split("\\.");
			liste.add(new DefinitionPermission(code, (String) speciale[1], morceaux[0], morceaux[1]));
		}
		return liste;
	}

	public static List<DefinitionRole> rolesParDefaut() {
		List<DefinitionRole> roles = new ArrayList<>();
		for (int i = 0; i < ROLES.size(); i++) {
			String code = ROLES.get(i);
			Set<String> droits = new LinkedHashSet<>();
			for (Map.Entry<Module, String[]> ligne : MATRICE.entrySet()) {
				String niveau = ligne.getValue()[i];
				for (ActionStandard action : actionsDuNiveau(niveau, code)) {
					droits.add(ligne.getKey().cle + "." + action.code);
				}
			}
			for (Object[] speciale : SPECIALES) {
				@SuppressWarnings("unchecked")
				List<String> recepteurs = (List<String>) speciale[2];
				if (recepteurs.contains(code)) {
					droits.add((String) speciale[0]);
				}
			}
			roles.add(new DefinitionRole(code, LIBELLES.get(code), DESCRIPTIONS.get(code), ROLES_PERIMETRE_GLOBAL.contains(code), ROLES_A2F.contains(code), droits));
		}
		return roles;
	}

	private static Set<ActionStandard> actionsDuNiveau(String niveau, String role) {
		Set<ActionStandard> actions = new LinkedHashSet<>();
		switch (niveau) {
			case "T" -> actions.addAll(List.of(ActionStandard.values()));
			case "C" -> actions.addAll(List.of(ActionStandard.VOIR, ActionStandard.CREER, ActionStandard.MODIFIER));
			case "V" -> actions.addAll(List.of(ActionStandard.VOIR, ActionStandard.VALIDER, ActionStandard.ANNULER));
			case "CV" -> actions.addAll(List.of(ActionStandard.VOIR, ActionStandard.CREER, ActionStandard.MODIFIER,
					ActionStandard.VALIDER, ActionStandard.ANNULER));
			case "L" -> actions.add(ActionStandard.VOIR);
			default -> {
			}
		}
		if (!actions.isEmpty() && ROLES_EXPORT.contains(role)) {
			actions.add(ActionStandard.EXPORTER);
		}
		return actions;
	}

}
