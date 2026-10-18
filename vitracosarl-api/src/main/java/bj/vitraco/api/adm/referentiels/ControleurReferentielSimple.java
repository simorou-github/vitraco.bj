package bj.vitraco.api.adm.referentiels;

import bj.vitraco.api.common.Pagination;
import bj.vitraco.api.common.ReponseApi;
import bj.vitraco.api.common.ReponsePaginee;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Les listes de choix (banques, catégories, centres de coût) servent à tous les modules : la
 * lecture est ouverte à tout utilisateur connecté, l'écriture demande les droits d'administration.
 */
abstract class ControleurReferentielSimple<E extends ReferentielSimple> {

	private final ServiceReferentielSimple<E> service;

	protected ControleurReferentielSimple(ServiceReferentielSimple<E> service) {
		this.service = service;
	}

	@GetMapping
	public ReponsePaginee<ReferentielSimpleDto> lister(@RequestParam(required = false) String search,
													   @RequestParam(required = false) Boolean actif,
													   @RequestParam(required = false) Integer page,
													   @RequestParam(name = "per_page", required = false) Integer parPage) {
		var pagination = Pagination.de(page, parPage, Sort.by("libelle"));
		return ReponsePaginee.de(service.lister(new ServiceReferentielSimple.Filtre(search, actif), pagination), e -> e);
	}

	@GetMapping("/{id}")
	public ReponseApi<ReferentielSimpleDto> detail(@PathVariable Long id) {
		return ReponseApi.de(service.detail(id));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAuthority('administration.creer')")
	public ReponseApi<ReferentielSimpleDto> creer(@Valid @RequestBody ReferentielSimpleRequete requete) {
		return ReponseApi.de("Élément créé.", service.creer(requete));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('administration.modifier')")
	public ReponseApi<ReferentielSimpleDto> modifier(@PathVariable Long id, @Valid @RequestBody ReferentielSimpleRequete requete) {
		return ReponseApi.de("Élément mis à jour.", service.modifier(id, requete));
	}

	@PostMapping("/{id}/desactiver")
	@PreAuthorize("hasAuthority('administration.modifier')")
	public ReponseApi<ReferentielSimpleDto> desactiver(@PathVariable Long id) {
		return ReponseApi.de("Élément désactivé.", service.desactiver(id));
	}

	@PostMapping("/{id}/reactiver")
	@PreAuthorize("hasAuthority('administration.modifier')")
	public ReponseApi<ReferentielSimpleDto> reactiver(@PathVariable Long id) {
		return ReponseApi.de("Élément réactivé.", service.reactiver(id));
	}

}
