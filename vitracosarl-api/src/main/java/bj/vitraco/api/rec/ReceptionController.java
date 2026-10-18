package bj.vitraco.api.rec;

import bj.vitraco.api.common.Pagination;
import bj.vitraco.api.common.ReponseApi;
import bj.vitraco.api.common.ReponsePaginee;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/receptions")
class ReceptionController {

	private final ReceptionService service;

	ReceptionController(ReceptionService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('receptions.voir')")
	ReponsePaginee<ReceptionDto.Liste> lister(@RequestParam(required = false) String statut,
											  @RequestParam(name = "arrivage_id", required = false) Long arrivageId,
											  @RequestParam(name = "emplacement_id", required = false) Long emplacementId,
											  @RequestParam(required = false) String search,
											  @RequestParam(required = false) Integer page,
											  @RequestParam(name = "per_page", required = false) Integer parPage) {
		return ReponsePaginee.de(service.lister(new ReceptionService.Filtre(statut, arrivageId, emplacementId, search), Pagination.de(page, parPage, Sort.unsorted())), r -> r);
	}

	@GetMapping("/arrivages-a-receptionner")
	@PreAuthorize("hasAuthority('receptions.voir')")
	ReponseApi<List<ReceptionDto.ArrivageAReceptionner>> arrivagesAReceptionner() {
		return ReponseApi.de(service.arrivagesAReceptionner());
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('receptions.voir')")
	ReponseApi<ReceptionDto> detail(@PathVariable Long id) {
		return ReponseApi.de(service.detail(id));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAuthority('receptions.creer')")
	ReponseApi<ReceptionDto> creer(@Valid @RequestBody ReceptionRequete requete) {
		return ReponseApi.de("Réception ouverte.", service.creer(requete));
	}

	@PostMapping("/{id}/chassis")
	@PreAuthorize("hasAuthority('receptions.modifier')")
	ReponseApi<ReceptionDto> scanner(@PathVariable Long id, @Valid @RequestBody ScanRequete scan) {
		return ReponseApi.de("Châssis ajouté.", service.scanner(id, scan));
	}

	@DeleteMapping("/{id}/chassis/{ligneId}")
	@PreAuthorize("hasAuthority('receptions.modifier')")
	ReponseApi<ReceptionDto> retirer(@PathVariable Long id, @PathVariable Long ligneId) {
		return ReponseApi.de("Châssis retiré de la réception.", service.retirer(id, ligneId));
	}

	@PostMapping("/{id}/valider")
	@PreAuthorize("hasAuthority('receptions.valider')")
	ReponseApi<ReceptionDto> valider(@PathVariable Long id) {
		return ReponseApi.de("Réception validée : les motos sont en stock.", service.valider(id));
	}

	@GetMapping("/{id}/pv")
	@PreAuthorize("hasAuthority('receptions.voir')")
	ResponseEntity<byte[]> pv(@PathVariable Long id) {
		ReceptionDto r = service.detail(id);
		return ResponseEntity.ok()
				.header("Content-Disposition", "inline; filename=\"PV-" + r.numero() + ".pdf\"")
				.contentType(MediaType.APPLICATION_PDF)
				.body(PvReception.generer(r));
	}

}
