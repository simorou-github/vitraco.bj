package bj.vitraco.api.adm.parametres;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Paramètres fiscaux (EF-ADM-05) et de contrôle (EF-ADM-06). Aucune valeur par défaut n'est
 * inventée : les seuils et les taux sont à fournir par VITRACO et par la DGI (SRS §15, points
 * ouverts). Tant qu'un paramètre n'est pas renseigné, le module qui en dépend doit le signaler.
 * Le jeton e-MECeF lui-même n'est pas stocké ici : il va dans le coffre de secrets (SRS §11).
 */
public final class CatalogueParametres {

	public static final String IFU = "fiscal.ifu";
	public static final String JETON_EMECEF_EXPIRATION = "fiscal.jeton_emecef_expiration";
	public static final String DELAI_NORMALISATION_HEURES = "fiscal.delai_normalisation_heures";
	public static final String TAUX_AIB_A = "fiscal.taux_aib_a";
	public static final String TAUX_AIB_B = "fiscal.taux_aib_b";
	public static final String DELAI_DEPOT_CHEQUE_JOURS = "controle.delai_depot_cheque_jours";
	public static final String DELAI_RECEPTION_TRANSFERT_JOURS = "controle.delai_reception_transfert_jours";
	public static final String SEUIL_REMISE_POURCENT = "controle.seuil_remise_pourcent";
	public static final String SEUIL_ECART_INVENTAIRE = "controle.seuil_ecart_inventaire";
	public static final String SEUIL_JUSTIFICATIF_DEPENSE = "controle.seuil_justificatif_depense";
	public static final String RECEPTION_UTILISATEURS_DIFFERENTS = "controle.reception_utilisateurs_differents";
	public static final String TRANSFERT_UTILISATEURS_DIFFERENTS = "controle.transfert_utilisateurs_differents";

	/** Clé du taux d'un groupe de taxation e-MECeF, de A à F. */
	public static String tauxTaxation(char groupe) {
		return "fiscal.taux_taxation_" + Character.toLowerCase(groupe);
	}

	private static final Map<String, DefinitionParametre> DEFINITIONS = new LinkedHashMap<>();

	private static void ajouter(String cle, GroupeParametre groupe, String libelle, String aide, TypeParametre type, String unite,
								String min, String max) {
		ajouter(cle, groupe, libelle, aide, type, unite, min, max, null);
	}

	private static void ajouter(String cle, GroupeParametre groupe, String libelle, String aide, TypeParametre type, String unite,
								String min, String max, String defaut) {
		DEFINITIONS.put(cle, new DefinitionParametre(cle, groupe, libelle, aide, type, unite,
				min == null ? null : new BigDecimal(min), max == null ? null : new BigDecimal(max), defaut));
	}

	static {
		ajouter(IFU, GroupeParametre.FISCAL, "IFU de VITRACO", "Identifiant fiscal unique, repris sur chaque facture normalisée.",
				TypeParametre.TEXTE, null, null, null);
		ajouter(JETON_EMECEF_EXPIRATION, GroupeParametre.FISCAL, "Expiration du jeton e-MECeF",
				"Date de fin de validité du jeton : l'administrateur est alerté 30 jours avant.", TypeParametre.DATE, null, null, null);
		ajouter(DELAI_NORMALISATION_HEURES, GroupeParametre.FISCAL, "Délai maximal de normalisation",
				"Au-delà, une facture non normalisée est signalée.", TypeParametre.ENTIER, "heures", "1", "720");
		for (char g = 'A'; g <= 'F'; g++) {
			ajouter(tauxTaxation(g), GroupeParametre.FISCAL, "Taux du groupe de taxation " + g,
					"À confirmer avec la DGI ; l'écart avec les taux publiés par e-MECeF est signalé.", TypeParametre.DECIMAL, "%", "0", "100");
		}
		ajouter(TAUX_AIB_A, GroupeParametre.FISCAL, "Taux d'AIB, groupe A", "Acompte sur impôt, groupe A.", TypeParametre.DECIMAL, "%", "0", "100");
		ajouter(TAUX_AIB_B, GroupeParametre.FISCAL, "Taux d'AIB, groupe B", "Acompte sur impôt, groupe B.", TypeParametre.DECIMAL, "%", "0", "100");

		ajouter(DELAI_DEPOT_CHEQUE_JOURS, GroupeParametre.CONTROLE, "Délai de dépôt d'un chèque",
				"Un chèque reçu et non déposé après ce délai déclenche une alerte.", TypeParametre.ENTIER, "jours", "1", "365");
		ajouter(DELAI_RECEPTION_TRANSFERT_JOURS, GroupeParametre.CONTROLE, "Délai de réception d'un transfert",
				"Un transfert expédié et non reçu après ce délai déclenche une alerte.", TypeParametre.ENTIER, "jours", "1", "365");
		ajouter(SEUIL_REMISE_POURCENT, GroupeParametre.CONTROLE, "Seuil de remise",
				"Au-delà, la remise exige une validation hiérarchique. Un seuil propre à chaque rôle viendra avec le module des commandes.",
				TypeParametre.DECIMAL, "%", "0", "100");
		ajouter(SEUIL_ECART_INVENTAIRE, GroupeParametre.CONTROLE, "Seuil d'écart d'inventaire",
				"Écart toléré, en nombre de châssis, avant que la clôture exige une validation.", TypeParametre.ENTIER, "châssis", "0", "10000");
		ajouter(SEUIL_JUSTIFICATIF_DEPENSE, GroupeParametre.CONTROLE, "Seuil de justificatif de dépense",
				"Au-delà de ce montant, un justificatif est obligatoire et la dépense part en validation.", TypeParametre.ENTIER, "FCFA", "0", "1000000000");
		ajouter(RECEPTION_UTILISATEURS_DIFFERENTS, GroupeParametre.CONTROLE, "Réception validée par un autre utilisateur",
				"Oui : la personne qui ouvre une réception ne peut pas la valider elle-même (séparation des tâches).",
				TypeParametre.BOOLEEN, null, null, null, "true");
		ajouter(TRANSFERT_UTILISATEURS_DIFFERENTS, GroupeParametre.CONTROLE, "Transfert réceptionné par un autre utilisateur",
				"Oui : la personne qui a expédié un transfert ne peut pas en réceptionner les châssis (séparation des tâches).",
				TypeParametre.BOOLEEN, null, null, null, "true");
	}

	private CatalogueParametres() {
	}

	public static List<DefinitionParametre> definitions() {
		return new ArrayList<>(DEFINITIONS.values());
	}

	public static Optional<DefinitionParametre> definition(String cle) {
		return Optional.ofNullable(DEFINITIONS.get(cle));
	}

}
