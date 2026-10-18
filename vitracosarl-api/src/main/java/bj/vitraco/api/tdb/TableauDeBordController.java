package bj.vitraco.api.tdb;

import bj.vitraco.api.common.ReponseApi;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tableau-de-bord")
class TableauDeBordController {

	private final TableauDeBordService service;
	private final TableauDeBordMetierService metier;

	TableauDeBordController(TableauDeBordService service, TableauDeBordMetierService metier) {
		this.service = service;
		this.metier = metier;
	}

	@GetMapping("/administration")
	@PreAuthorize("hasAuthority('administration.voir')")
	ReponseApi<SyntheseAdministration> administration(Authentication auth) {
		boolean journal = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("journal_audit.voir"));
		return ReponseApi.de(service.administration(journal));
	}

	/** Domaine Commercial : de quoi vendre aujourd'hui, dans les emplacements de la personne. */
	@GetMapping("/commercial")
	@PreAuthorize("hasAuthority('clients.voir')")
	ReponseApi<SyntheseCommerciale> commercial() {
		return ReponseApi.de(metier.commerciale());
	}

	/** Domaine Management : stock consolidé, approvisionnements, réceptions et points de contrôle. */
	@GetMapping("/management")
	@PreAuthorize("hasAuthority('approvisionnements.voir') and hasAuthority('stock.voir')")
	ReponseApi<SyntheseManagement> management() {
		return ReponseApi.de(metier.management());
	}

}
