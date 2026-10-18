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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/fournisseurs")
class ControleurFournisseurs {

	private static final String LECTURE = "hasAnyAuthority('administration.voir','approvisionnements.voir','receptions.voir')";

	private final ServiceFournisseurs service;

	ControleurFournisseurs(ServiceFournisseurs service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize(LECTURE)
	ReponsePaginee<FournisseurDto> lister(@RequestParam(required = false) String search,
										  @RequestParam(required = false) Boolean actif,
										  @RequestParam(required = false) Integer page,
										  @RequestParam(name = "per_page", required = false) Integer parPage) {
		return ReponsePaginee.de(service.lister(search, actif, Pagination.de(page, parPage, Sort.by("raisonSociale"))), f -> f);
	}

	@GetMapping("/{id}")
	@PreAuthorize(LECTURE)
	ReponseApi<FournisseurDto> detail(@PathVariable Long id) {
		return ReponseApi.de(service.detail(id));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAuthority('administration.creer')")
	ReponseApi<FournisseurDto> creer(@Valid @RequestBody FournisseurRequete requete) {
		return ReponseApi.de("Fournisseur créé.", service.creer(requete));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('administration.modifier')")
	ReponseApi<FournisseurDto> modifier(@PathVariable Long id, @Valid @RequestBody FournisseurRequete requete) {
		return ReponseApi.de("Fournisseur mis à jour.", service.modifier(id, requete));
	}

	@PostMapping("/{id}/desactiver")
	@PreAuthorize("hasAuthority('administration.modifier')")
	ReponseApi<FournisseurDto> desactiver(@PathVariable Long id) {
		return ReponseApi.de("Fournisseur désactivé.", service.desactiver(id));
	}

	@PostMapping("/{id}/reactiver")
	@PreAuthorize("hasAuthority('administration.modifier')")
	ReponseApi<FournisseurDto> reactiver(@PathVariable Long id) {
		return ReponseApi.de("Fournisseur réactivé.", service.reactiver(id));
	}

}
