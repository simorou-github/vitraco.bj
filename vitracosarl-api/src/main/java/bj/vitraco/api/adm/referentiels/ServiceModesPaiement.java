package bj.vitraco.api.adm.referentiels;

import bj.vitraco.api.adm.utilisateurs.UtilisateurRepository;
import bj.vitraco.api.aud.EntreeAudit;
import bj.vitraco.api.aud.JournalAudit;
import bj.vitraco.api.common.CodeModule;
import bj.vitraco.api.common.ErreurMetierException;
import bj.vitraco.api.common.ErreurValidationException;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
class ServiceModesPaiement {

	private final ModePaiementRepository modes;
	private final UtilisateurRepository utilisateurs;
	private final JournalAudit journal;

	ServiceModesPaiement(ModePaiementRepository modes, UtilisateurRepository utilisateurs, JournalAudit journal) {
		this.modes = modes;
		this.utilisateurs = utilisateurs;
		this.journal = journal;
	}

	@Transactional(readOnly = true)
	Page<ModePaiementDto> lister(String recherche, Boolean actif, Pageable pagination) {
		Specification<ModePaiement> spec = (racine, requete, cb) -> {
			List<Predicate> criteres = new ArrayList<>();
			if (recherche != null && !recherche.isBlank()) {
				String motif = "%" + recherche.trim().toLowerCase(Locale.ROOT) + "%";
				criteres.add(cb.or(cb.like(cb.lower(racine.get("code")), motif), cb.like(cb.lower(racine.get("libelle")), motif)));
			}
			if (actif != null) {
				criteres.add(cb.equal(racine.get("actif"), actif));
			}
			return cb.and(criteres.toArray(new Predicate[0]));
		};
		return modes.findAll(spec, pagination).map(ModePaiementDto::de);
	}

	@Transactional(readOnly = true)
	ModePaiementDto detail(Long id) {
		return ModePaiementDto.de(trouver(id));
	}

	@Transactional
	ModePaiementDto creer(ModePaiementRequete requete) {
		String code = Acteurs.code(requete.code());
		if (modes.codeUtilise(code)) {
			throw new ErreurValidationException("code", "Ce code est déjà utilisé.");
		}
		ModePaiement m = new ModePaiement(code, requete.libelle().trim(), requete.typeDgi());
		appliquer(m, requete);
		m.setCreePar(Acteurs.courant(utilisateurs));
		modes.saveAndFlush(m);
		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, "mode_paiement.cree", "ModePaiement", String.valueOf(m.getId()))
				.avecValeurs(null, resume(m)));
		return ModePaiementDto.de(m);
	}

	@Transactional
	ModePaiementDto modifier(Long id, ModePaiementRequete requete) {
		ModePaiement m = trouver(id);
		if (!m.getCode().equalsIgnoreCase(requete.code().trim())) {
			throw new ErreurValidationException("code", "Le code ne peut pas être modifié.");
		}
		if (m.isSysteme() && m.getTypeDgi() != requete.typeDgi()) {
			throw new ErreurValidationException("type_dgi", "Le type DGI d'un mode livré avec l'application ne peut pas être modifié.");
		}
		Map<String, Object> avant = resume(m);
		m.setLibelle(requete.libelle().trim());
		m.setTypeDgi(requete.typeDgi());
		appliquer(m, requete);
		m.setModifiePar(Acteurs.courant(utilisateurs));
		modes.saveAndFlush(m);
		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, "mode_paiement.modifie", "ModePaiement", String.valueOf(id))
				.avecValeurs(avant, resume(m)));
		return ModePaiementDto.de(m);
	}

	@Transactional
	ModePaiementDto desactiver(Long id) {
		return changerEtat(id, false);
	}

	@Transactional
	ModePaiementDto reactiver(Long id) {
		return changerEtat(id, true);
	}

	private ModePaiementDto changerEtat(Long id, boolean actif) {
		ModePaiement m = trouver(id);
		if (m.isActif() == actif) {
			throw new ErreurMetierException(actif ? "DEJA_ACTIF" : "DEJA_DESACTIVE",
					actif ? "Ce mode de paiement est déjà actif." : "Ce mode de paiement est déjà désactivé.");
		}
		m.setActif(actif);
		m.setModifiePar(Acteurs.courant(utilisateurs));
		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, actif ? "mode_paiement.reactive" : "mode_paiement.desactive", "ModePaiement", String.valueOf(id))
				.avecValeurs(Map.of("actif", !actif), Map.of("actif", actif)));
		return ModePaiementDto.de(m);
	}

	private static void appliquer(ModePaiement m, ModePaiementRequete requete) {
		m.setJustificatif(Acteurs.vide(requete.justificatif()));
		m.setJustificatifObligatoire(requete.justificatifObligatoire() == null || requete.justificatifObligatoire());
	}

	private ModePaiement trouver(Long id) {
		return modes.findById(id)
				.orElseThrow(() -> new ErreurMetierException("INTROUVABLE", "Mode de paiement introuvable.", HttpStatus.NOT_FOUND));
	}

	private static Map<String, Object> resume(ModePaiement m) {
		Map<String, Object> valeurs = new LinkedHashMap<>();
		valeurs.put("code", m.getCode());
		valeurs.put("libelle", m.getLibelle());
		valeurs.put("type_dgi", m.getTypeDgi());
		valeurs.put("justificatif", m.getJustificatif());
		valeurs.put("justificatif_obligatoire", m.isJustificatifObligatoire());
		valeurs.put("actif", m.isActif());
		return valeurs;
	}

}
