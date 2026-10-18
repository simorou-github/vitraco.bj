package bj.vitraco.api.cat.chassis;

import java.util.List;

/** La fiche d'un châssis : son état courant, toute son histoire datée avec son auteur (US-012) et ses corrections de numéro. */
public record ChassisFicheDto(ChassisDto chassis, List<MouvementsStock.Evenement> historique, List<CorrectionNumeroService.Correction> corrections) {
}
