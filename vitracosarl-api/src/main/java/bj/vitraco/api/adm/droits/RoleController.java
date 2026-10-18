package bj.vitraco.api.adm.droits;

import bj.vitraco.api.adm.droits.RoleDto.PermissionDto;
import bj.vitraco.api.common.Pagination;
import bj.vitraco.api.common.ReponseApi;
import bj.vitraco.api.common.ReponsePaginee;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1")
class RoleController {

	private final RoleService service;

	RoleController(RoleService service) {
		this.service = service;
	}

	@GetMapping("/roles")
	@PreAuthorize("hasAuthority('administration.voir')")
	ReponsePaginee<RoleDto> lister(@RequestParam(required = false) Integer page,
								   @RequestParam(name = "per_page", required = false) Integer parPage) {
		return ReponsePaginee.de(service.lister(Pagination.de(page, parPage, Sort.by("libelle"))), r -> r);
	}

	@GetMapping("/roles/{id}")
	@PreAuthorize("hasAuthority('administration.voir')")
	ReponseApi<RoleDto> detail(@PathVariable Long id) {
		return ReponseApi.de(service.detail(id));
	}

	@PostMapping("/roles")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAuthority('administration.creer')")
	ReponseApi<RoleDto> creer(@Valid @RequestBody RoleRequete requete) {
		return ReponseApi.de("Rôle créé.", service.creer(requete));
	}

	@PutMapping("/roles/{id}")
	@PreAuthorize("hasAuthority('administration.modifier')")
	ReponseApi<RoleDto> modifier(@PathVariable Long id, @Valid @RequestBody RoleRequete requete) {
		return ReponseApi.de("Rôle mis à jour.", service.modifier(id, requete));
	}

	@DeleteMapping("/roles/{id}")
	@PreAuthorize("hasAuthority('administration.annuler')")
	ResponseEntity<Void> supprimer(@PathVariable Long id) {
		service.supprimer(id);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/permissions")
	@PreAuthorize("hasAuthority('administration.voir')")
	ReponseApi<List<PermissionDto>> permissions() {
		return ReponseApi.de(service.toutesLesPermissions());
	}

}
