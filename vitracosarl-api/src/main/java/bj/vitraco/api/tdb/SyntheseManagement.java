package bj.vitraco.api.tdb;

import bj.vitraco.api.stk.StockDto;
import bj.vitraco.api.tdb.SyntheseAdministration.Alerte;

import java.util.List;

/** Le point de vue de la Direction : où en est le stock, ce qui arrive, ce qui bloque. */
public record SyntheseManagement(
		Stock stock,
		Approvisionnements approvisionnements,
		Receptions receptions,
		Controle controle,
		List<Alerte> alertes) {

	/** Le total est le stock global VITRACO pour une vue consolidée, celui du périmètre sinon. */
	public record Stock(boolean consolide, StockDto.Indicateurs indicateurs, int global, StockDto.Importation importation,
						List<StockDto.LigneEmplacement> emplacements, Valorisation valorisation) {
	}

	/** Absente sans le droit de voir les prix d'achat. Les motos sans coût connu ne sont pas valorisées. */
	public record Valorisation(long montantFcfa, long chassisSansCout) {
	}

	public record Approvisionnements(long commandesEnCours, List<Statut> parStatut, long arrivagesEnRetard, List<ArrivageEnRetard> retards,
									 long arrivagesAReceptionner) {
	}

	public record Statut(String code, String libelle, long nombre) {
	}

	public record ArrivageEnRetard(Long id, String numeroBl, String commande, String fournisseur, long retardJours) {
	}

	public record Receptions(long brouillons, long valideesSur30Jours, long motosRecuesSur30Jours) {
	}

	public record Controle(long chassisBloques, long bloqueDepuisJoursMax, long correctionsEnAttente, long transfertsEnTransit, long transfertsEnRetard) {
	}

}
