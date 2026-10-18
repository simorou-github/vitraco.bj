package bj.vitraco.api.stk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class ImportStockDto {

	private ImportStockDto() {
	}

	public record Rejet(int ligne, String numero, String motif) {
	}

	public record Repartition(Long emplacementId, String code, String nom, int nombre, long coutFcfa) {
	}

	public record Rapport(Long id, String nomFichier, int nbLignes, int nbImportees, int nbRejetees, boolean simulation, List<Repartition> repartition,
						  List<Rejet> rejets, long valeurCoutFcfa) {
	}

	public record Historique(Long id, String nomFichier, int nbLignes, int nbImportees, int nbRejetees, String auteur, Instant date) {
	}

	/** Une ligne du rapprochement : un châssis attendu et non pointé, ou pointé et non attendu. */
	public record Ecart(String numero, String detail) {
	}

	public record Rapprochement(Long id, Long emplacementId, String emplacement, int nbAttendus, int nbPointes, int nbConformes, List<Ecart> manquants,
								List<Ecart> inattendus, boolean ecartNul, String auteur, Instant date) {
	}

	public record RapprochementListe(Long id, String emplacement, int nbAttendus, int nbPointes, int nbConformes, int nbManquants, int nbInattendus,
									 boolean ecartNul, String auteur, Instant date) {
	}

	/** Valeur utilisée pour le coût lu dans le fichier (jamais exposée sans droit). */
	static BigDecimal coutOuNull(String texte) {
		if (texte == null || texte.isBlank()) {
			return null;
		}
		return new BigDecimal(texte.replaceAll("[\\s\\u00A0]", "").replace(',', '.'));
	}

}
