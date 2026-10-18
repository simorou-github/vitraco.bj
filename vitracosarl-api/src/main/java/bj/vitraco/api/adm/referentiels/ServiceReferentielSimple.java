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
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cycle de vie commun des référentiels à code et libellé : création, renommage, désactivation et
 * réactivation. Rien ne se supprime (EF-ADM-08) : un élément désactivé n'est plus proposé pour de
 * nouvelles saisies mais reste lisible dans l'historique.
 */
abstract class ServiceReferentielSimple<E extends ReferentielSimple> {

	record Filtre(String recherche, Boolean actif) {
	}

	private final ReferentielSimpleRepository<E> repository;
	private final UtilisateurRepository utilisateurs;
	private final JournalAudit journal;
	/** Type d'objet et préfixe d'action au journal, par exemple « Banque » / « banque ». */
	private final String objetType;
	private final String prefixeAction;

	protected ServiceReferentielSimple(ReferentielSimpleRepository<E> repository, UtilisateurRepository utilisateurs,
									   JournalAudit journal, String objetType, String prefixeAction) {
		this.repository = repository;
		this.utilisateurs = utilisateurs;
		this.journal = journal;
		this.objetType = objetType;
		this.prefixeAction = prefixeAction;
	}

	protected abstract E nouveau(String code, String libelle);

	@Transactional(readOnly = true)
	public Page<ReferentielSimpleDto> lister(Filtre filtre, Pageable pagination) {
		Specification<E> spec = (racine, requete, cb) -> {
			List<Predicate> criteres = new ArrayList<>();
			if (filtre.recherche() != null && !filtre.recherche().isBlank()) {
				String motif = "%" + filtre.recherche().trim().toLowerCase(Locale.ROOT) + "%";
				criteres.add(cb.or(cb.like(cb.lower(racine.get("code")), motif), cb.like(cb.lower(racine.get("libelle")), motif)));
			}
			if (filtre.actif() != null) {
				criteres.add(cb.equal(racine.get("actif"), filtre.actif()));
			}
			return cb.and(criteres.toArray(new Predicate[0]));
		};
		return repository.findAll(spec, pagination).map(ReferentielSimpleDto::de);
	}

	@Transactional(readOnly = true)
	public ReferentielSimpleDto detail(Long id) {
		return ReferentielSimpleDto.de(trouver(id));
	}

	@Transactional
	public ReferentielSimpleDto creer(ReferentielSimpleRequete requete) {
		String code = Acteurs.code(requete.code());
		if (repository.codeUtilise(code)) {
			throw new ErreurValidationException("code", "Ce code est déjà utilisé.");
		}
		E e = nouveau(code, requete.libelle().trim());
		e.setCreePar(Acteurs.courant(utilisateurs));
		repository.saveAndFlush(e);
		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, prefixeAction + ".cree", objetType, String.valueOf(e.getId()))
				.avecValeurs(null, resume(e)));
		return ReferentielSimpleDto.de(e);
	}

	@Transactional
	public ReferentielSimpleDto modifier(Long id, ReferentielSimpleRequete requete) {
		E e = trouver(id);
		if (!e.getCode().equalsIgnoreCase(requete.code().trim())) {
			throw new ErreurValidationException("code", "Le code ne peut pas être modifié.");
		}
		Map<String, Object> avant = resume(e);
		e.setLibelle(requete.libelle().trim());
		e.setModifiePar(Acteurs.courant(utilisateurs));
		repository.saveAndFlush(e);
		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, prefixeAction + ".modifie", objetType, String.valueOf(id))
				.avecValeurs(avant, resume(e)));
		return ReferentielSimpleDto.de(e);
	}

	@Transactional
	public ReferentielSimpleDto desactiver(Long id) {
		return changerEtat(id, false);
	}

	@Transactional
	public ReferentielSimpleDto reactiver(Long id) {
		return changerEtat(id, true);
	}

	private ReferentielSimpleDto changerEtat(Long id, boolean actif) {
		E e = trouver(id);
		if (e.isActif() == actif) {
			throw new ErreurMetierException(actif ? "DEJA_ACTIF" : "DEJA_DESACTIVE",
					actif ? "Cet élément est déjà actif." : "Cet élément est déjà désactivé.");
		}
		e.setActif(actif);
		e.setModifiePar(Acteurs.courant(utilisateurs));
		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, prefixeAction + (actif ? ".reactive" : ".desactive"), objetType, String.valueOf(id))
				.avecValeurs(Map.of("actif", !actif), Map.of("actif", actif)));
		return ReferentielSimpleDto.de(e);
	}

	private E trouver(Long id) {
		return repository.findById(id)
				.orElseThrow(() -> new ErreurMetierException("INTROUVABLE", objetType + " introuvable.", HttpStatus.NOT_FOUND));
	}

	private static Map<String, Object> resume(ReferentielSimple e) {
		return Map.of("code", e.getCode(), "libelle", e.getLibelle(), "actif", e.isActif());
	}

}
