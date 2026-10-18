package bj.vitraco.api.adm.utilisateurs;

import bj.vitraco.api.common.Pagination;
import bj.vitraco.api.common.ReponseApi;
import bj.vitraco.api.common.ReponsePaginee;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/v1/utilisateurs")
class UtilisateurController {

	private final UtilisateurService service;

	UtilisateurController(UtilisateurService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('administration.voir')")
	ReponsePaginee<UtilisateurDto> lister(@RequestParam(required = false) String search,
										  @RequestParam(required = false) String role,
										  @RequestParam(required = false) StatutUtilisateur statut,
										  @RequestParam(required = false) Integer page,
										  @RequestParam(name = "per_page", required = false) Integer parPage) {
		var pagination = Pagination.de(page, parPage, Sort.by(Sort.Direction.DESC, "creeLe", "id"));
		return ReponsePaginee.de(service.lister(new UtilisateurService.Filtre(search, role, statut), pagination), u -> u);
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('administration.voir')")
	ReponseApi<UtilisateurDto> detail(@PathVariable Long id) {
		return ReponseApi.de(service.detail(id));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAuthority('administration.creer')")
	ReponseApi<UtilisateurDto> creer(@Valid @RequestBody UtilisateurRequete requete) {
		return ReponseApi.de("Utilisateur créé. Un lien d'activation lui a été envoyé.", service.creer(requete));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('administration.modifier')")
	ReponseApi<UtilisateurDto> modifier(@PathVariable Long id, @Valid @RequestBody UtilisateurRequete requete) {
		return ReponseApi.de("Utilisateur mis à jour.", service.modifier(id, requete));
	}

	@PostMapping("/{id}/suspendre")
	@PreAuthorize("hasAuthority('administration.modifier')")
	ReponseApi<UtilisateurDto> suspendre(@PathVariable Long id) {
		return ReponseApi.de("Compte suspendu.", service.suspendre(id));
	}

	@PostMapping("/{id}/reactiver")
	@PreAuthorize("hasAuthority('administration.modifier')")
	ReponseApi<UtilisateurDto> reactiver(@PathVariable Long id) {
		return ReponseApi.de("Compte réactivé.", service.reactiver(id));
	}

	@PostMapping("/{id}/reinitialiser-a2f")
	@PreAuthorize("hasAuthority('administration.modifier')")
	ResponseEntity<Void> reinitialiserA2f(@PathVariable Long id) {
		service.reinitialiserA2f(id);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{id}/renvoyer-activation")
	@PreAuthorize("hasAuthority('administration.modifier')")
	ResponseEntity<Void> renvoyerActivation(@PathVariable Long id) {
		service.renvoyerActivation(id);
		return ResponseEntity.noContent().build();
	}

}
