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

/** Lecture ouverte à tout utilisateur connecté (listes de choix des règlements) ; écriture réservée à l'administration. */
@RestController
@RequestMapping("/api/v1/modes-paiement")
class ControleurModesPaiement {

	private final ServiceModesPaiement service;

	ControleurModesPaiement(ServiceModesPaiement service) {
		this.service = service;
	}

	@GetMapping
	ReponsePaginee<ModePaiementDto> lister(@RequestParam(required = false) String search,
										   @RequestParam(required = false) Boolean actif,
										   @RequestParam(required = false) Integer page,
										   @RequestParam(name = "per_page", required = false) Integer parPage) {
		return ReponsePaginee.de(service.lister(search, actif, Pagination.de(page, parPage, Sort.by("libelle"))), m -> m);
	}

	@GetMapping("/{id}")
	ReponseApi<ModePaiementDto> detail(@PathVariable Long id) {
		return ReponseApi.de(service.detail(id));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAuthority('administration.creer')")
	ReponseApi<ModePaiementDto> creer(@Valid @RequestBody ModePaiementRequete requete) {
		return ReponseApi.de("Mode de paiement créé.", service.creer(requete));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('administration.modifier')")
	ReponseApi<ModePaiementDto> modifier(@PathVariable Long id, @Valid @RequestBody ModePaiementRequete requete) {
		return ReponseApi.de("Mode de paiement mis à jour.", service.modifier(id, requete));
	}

	@PostMapping("/{id}/desactiver")
	@PreAuthorize("hasAuthority('administration.modifier')")
	ReponseApi<ModePaiementDto> desactiver(@PathVariable Long id) {
		return ReponseApi.de("Mode de paiement désactivé.", service.desactiver(id));
	}

	@PostMapping("/{id}/reactiver")
	@PreAuthorize("hasAuthority('administration.modifier')")
	ReponseApi<ModePaiementDto> reactiver(@PathVariable Long id) {
		return ReponseApi.de("Mode de paiement réactivé.", service.reactiver(id));
	}

}
