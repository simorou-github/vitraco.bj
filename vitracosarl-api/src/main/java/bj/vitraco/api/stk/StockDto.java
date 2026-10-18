package bj.vitraco.api.stk;

import java.time.Instant;
import java.util.List;

/**
 * Le stock est calculé à partir des châssis, jamais saisi (EF-STK-04). Définitions du SRS §5.6 : le physique compte les
 * motos disponibles, réservées, en préparation et bloquées d'un site ; le disponible est le physique moins le réservé
 * et le bloqué.
 */
public final class StockDto {

	private StockDto() {
	}

	public record Indicateurs(int physique, int reserve, int bloque, int disponible, int enTransitInterne, int depotVente, int retourne) {

		static Indicateurs vide() {
			return new Indicateurs(0, 0, 0, 0, 0, 0, 0);
		}

		Indicateurs plus(Indicateurs autre) {
			return new Indicateurs(physique + autre.physique, reserve + autre.reserve, bloque + autre.bloque, disponible + autre.disponible,
					enTransitInterne + autre.enTransitInterne, depotVente + autre.depotVente, retourne + autre.retourne);
		}

		/** Sites VITRACO + transit interne + dépôt-vente : chaque châssis est compté une seule fois. */
		public int global() {
			return physique + enTransitInterne + depotVente;
		}

	}

	/** Motos pas encore reçues : affichées à part, hors stock. */
	public record Importation(int enTransitFournisseur, int aReceptionner) {
	}

	public record LigneEmplacement(Long id, String code, String nom, String type, Indicateurs indicateurs) {
	}

	public record LigneModele(Long modeleId, String modele, String codeArticle, Indicateurs indicateurs, Importation importation) {
	}

	/** consolide : la personne voit tous les emplacements, le total est alors le stock global VITRACO. */
	public record Synthese(boolean consolide, Indicateurs total, int global, Importation importation, List<LigneEmplacement> emplacements) {
	}

	public record Bloque(Long id, String numero, String modele, String codeArticle, Long emplacementId, String emplacement, Instant depuis,
						 String motif, String auteur, long joursDeBlocage) {
	}

	public record Mouvement(Long id, Instant date, String type, String chassisNumero, Long chassisId, String modele, String source, String destination,
							String statutAvant, String statutApres, String documentType, Long documentId, String motif, String auteur) {
	}

}
