package bj.vitraco.api.adm.emplacements;

import bj.vitraco.api.adm.utilisateurs.Utilisateur;
import bj.vitraco.api.adm.utilisateurs.UtilisateurRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Périmètre de données d'un utilisateur (EF-ADM-13) : ses emplacements actifs, ou tous si l'un
 * de ses rôles est à périmètre consolidé. Chaque module métier s'y réfère pour borner ce qu'il
 * lit et ce qu'il écrit ; l'administration des emplacements n'en dépend pas.
 */
@Service
public class PerimetreService {

	public record Perimetre(boolean global, Set<Long> emplacementIds) {

		public boolean autorise(Long emplacementId) {
			return emplacementIds.contains(emplacementId);
		}

	}

	private final UtilisateurRepository utilisateurs;
	private final EmplacementRepository emplacements;

	PerimetreService(UtilisateurRepository utilisateurs, EmplacementRepository emplacements) {
		this.utilisateurs = utilisateurs;
		this.emplacements = emplacements;
	}

	@Transactional(readOnly = true)
	public Perimetre perimetreDe(Long utilisateurId) {
		Utilisateur u = utilisateurs.findById(utilisateurId).orElseThrow();
		boolean global = u.aPerimetreGlobal();
		Set<Long> ids = global
				? emplacements.findByActifTrue().stream().map(Emplacement::getId).collect(Collectors.toSet())
				: u.getEmplacements().stream().filter(Emplacement::isActif).map(Emplacement::getId).collect(Collectors.toSet());
		return new Perimetre(global, ids);
	}

}
