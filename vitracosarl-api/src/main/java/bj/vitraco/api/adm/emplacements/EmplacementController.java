package bj.vitraco.api.adm.emplacements;

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
@RequestMapping("/api/v1/emplacements")
class EmplacementController {

	private final EmplacementService service;

	EmplacementController(EmplacementService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('administration.voir')")
	ReponsePaginee<EmplacementDto> lister(@RequestParam(required = false) String search,
										  @RequestParam(required = false) TypeEmplacement type,
										  @RequestParam(required = false) Boolean actif,
										  @RequestParam(required = false) Integer page,
										  @RequestParam(name = "per_page", required = false) Integer parPage) {
		var pagination = Pagination.de(page, parPage, Sort.by("type", "nom"));
		return ReponsePaginee.de(service.lister(new EmplacementService.Filtre(search, type, actif), pagination), e -> e);
	}

	/** Ouvert à tout utilisateur connecté : de quoi alimenter un filtre, rien de plus. */
	@GetMapping("/choix")
	ReponseApi<java.util.List<EmplacementRef>> choix(@RequestParam(required = false) TypeEmplacement type) {
		return ReponseApi.de(service.choix(type));
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('administration.voir')")
	ReponseApi<EmplacementDto> detail(@PathVariable Long id) {
		return ReponseApi.de(service.detail(id));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAuthority('administration.creer')")
	ReponseApi<EmplacementDto> creer(@Valid @RequestBody EmplacementRequete requete) {
		return ReponseApi.de("Emplacement créé.", service.creer(requete));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('administration.modifier')")
	ReponseApi<EmplacementDto> modifier(@PathVariable Long id, @Valid @RequestBody EmplacementRequete requete) {
		return ReponseApi.de("Emplacement mis à jour.", service.modifier(id, requete));
	}

	@PostMapping("/{id}/desactiver")
	@PreAuthorize("hasAuthority('administration.modifier')")
	ReponseApi<EmplacementDto> desactiver(@PathVariable Long id) {
		return ReponseApi.de("Emplacement désactivé.", service.desactiver(id));
	}

	@PostMapping("/{id}/reactiver")
	@PreAuthorize("hasAuthority('administration.modifier')")
	ReponseApi<EmplacementDto> reactiver(@PathVariable Long id) {
		return ReponseApi.de("Emplacement réactivé.", service.reactiver(id));
	}

}
