package bj.vitraco.api.adm.parametres;

import bj.vitraco.api.common.ReponseApi;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/parametres")
class ParametreController {

	private final ParametreService service;

	ParametreController(ParametreService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('administration.voir')")
	ReponseApi<List<ParametreDto>> lister() {
		return ReponseApi.de(service.lister());
	}

	@GetMapping("/{cle}/historique")
	@PreAuthorize("hasAuthority('administration.voir')")
	ReponseApi<List<ParametreDto.Version>> historique(@PathVariable String cle) {
		return ReponseApi.de(service.historique(cle));
	}

	@PutMapping("/{cle}")
	@PreAuthorize("hasAuthority('administration.modifier')")
	ReponseApi<List<ParametreDto>> modifier(@PathVariable String cle, @Valid @RequestBody ParametreRequete requete) {
		service.modifier(cle, requete);
		return ReponseApi.de("Paramètre enregistré.", service.lister());
	}

}
