package bj.vitraco.api.cat;

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

import java.util.List;

@RestController
@RequestMapping("/api/v1/modeles")
class ModeleController {

	private final ModeleService service;

	ModeleController(ModeleService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('catalogue.voir')")
	ReponsePaginee<ModeleDto> lister(@RequestParam(required = false) String search,
									 @RequestParam(required = false) String marque,
									 @RequestParam(required = false) Boolean actif,
									 @RequestParam(required = false) Integer page,
									 @RequestParam(name = "per_page", required = false) Integer parPage) {
		var pagination = Pagination.de(page, parPage, Sort.by("marque", "nom", "versionModele"));
		return ReponsePaginee.de(service.lister(new ModeleService.Filtre(search, marque, actif), pagination), m -> m);
	}

	@GetMapping("/marques")
	@PreAuthorize("hasAuthority('catalogue.voir')")
	ReponseApi<List<String>> marques() {
		return ReponseApi.de(service.marques());
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('catalogue.voir')")
	ReponseApi<ModeleDto> detail(@PathVariable Long id) {
		return ReponseApi.de(service.detail(id));
	}

	@GetMapping("/{id}/prix")
	@PreAuthorize("hasAuthority('catalogue.voir')")
	ReponseApi<List<PrixService.LignePrix>> historique(@PathVariable Long id) {
		return ReponseApi.de(service.historique(id));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAuthority('catalogue.creer')")
	ReponseApi<ModeleDto> creer(@Valid @RequestBody ModeleRequete requete) {
		return ReponseApi.de("Modèle créé.", service.creer(requete));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('catalogue.modifier')")
	ReponseApi<ModeleDto> modifier(@PathVariable Long id, @Valid @RequestBody ModeleModification requete) {
		return ReponseApi.de("Modèle mis à jour.", service.modifier(id, requete));
	}

	@PutMapping("/{id}/prix")
	@PreAuthorize("hasAuthority('catalogue.modifier')")
	ReponseApi<ModeleDto> changerPrix(@PathVariable Long id, @Valid @RequestBody ChangementPrixRequete requete) {
		return ReponseApi.de("Prix enregistré.", service.changerPrix(id, requete));
	}

	@PostMapping("/{id}/desactiver")
	@PreAuthorize("hasAuthority('catalogue.modifier')")
	ReponseApi<ModeleDto> desactiver(@PathVariable Long id) {
		return ReponseApi.de("Modèle désactivé.", service.desactiver(id));
	}

	@PostMapping("/{id}/reactiver")
	@PreAuthorize("hasAuthority('catalogue.modifier')")
	ReponseApi<ModeleDto> reactiver(@PathVariable Long id) {
		return ReponseApi.de("Modèle réactivé.", service.reactiver(id));
	}

}
