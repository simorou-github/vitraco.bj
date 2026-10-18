package bj.vitraco.api.stk;

import bj.vitraco.api.adm.emplacements.VerificationUsageEmplacement;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Un emplacement n'est libre que sans moto en stock, sans transfert en cours vers ou depuis lui et sans réception ouverte :
 * on ne désactive pas un magasin qui contient des motos, et une boutique ne change pas de mode tant qu'elle a du stock
 * (EF-ADM-08, EF-ADM-09).
 */
@Component
class UsageEmplacementStock implements VerificationUsageEmplacement {

	private final JdbcClient jdbc;

	UsageEmplacementStock(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public boolean aStockOuOperationEnCours(Long emplacementId) {
		return jdbc.sql("""
						SELECT EXISTS (SELECT 1 FROM chassis WHERE emplacement_id = :e AND statut NOT IN ('LIVREE', 'VENDUE_DEPOT_VENTE', 'SORTIE_PAR_AJUSTEMENT'))
						    OR EXISTS (SELECT 1 FROM transferts WHERE (source_id = :e OR destination_id = :e) AND statut IN ('INITIE', 'EXPEDIE'))
						    OR EXISTS (SELECT 1 FROM receptions WHERE emplacement_id = :e AND statut = 'BROUILLON')""")
				.param("e", emplacementId).query(Boolean.class).single();
	}

}
