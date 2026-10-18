package bj.vitraco.api.trf;

import bj.vitraco.api.common.Pagination;
import bj.vitraco.api.common.ReponseApi;
import bj.vitraco.api.common.ReponsePaginee;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

import java.util.List;

@RestController
@RequestMapping("/api/v1/transferts")
class TransfertController {

	private final TransfertService service;

	TransfertController(TransfertService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('transferts.voir')")
	ReponsePaginee<TransfertDto.Liste> lister(@RequestParam(required = false) String statut, @RequestParam(required = false) String vue,
											  @RequestParam(name = "source_id", required = false) Long sourceId,
											  @RequestParam(name = "destination_id", required = false) Long destinationId,
											  @RequestParam(required = false) String search, @RequestParam(required = false) Integer page,
											  @RequestParam(name = "per_page", required = false) Integer parPage) {
		return ReponsePaginee.de(service.lister(new TransfertService.Filtre(statut, sourceId, destinationId, vue, search), Pagination.de(page, parPage, Sort.unsorted())), t -> t);
	}

	@GetMapping("/chassis-transferables")
	@PreAuthorize("hasAuthority('transferts.creer')")
	ReponseApi<List<TransfertDto.Transferable>> transferables(@RequestParam(name = "source_id") Long sourceId, @RequestParam(required = false) String search,
															  @RequestParam(name = "modele_id", required = false) Long modeleId) {
		return ReponseApi.de(service.transferables(sourceId, search, modeleId));
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('transferts.voir')")
	ReponseApi<TransfertDto> detail(@PathVariable Long id) {
		return ReponseApi.de(service.detail(id));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAuthority('transferts.creer')")
	ReponseApi<TransfertDto> creer(@Valid @RequestBody TransfertRequete requete) {
		return ReponseApi.de("Transfert créé : aucun mouvement de stock tant qu'il n'est pas expédié.", service.creer(requete));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('transferts.creer')")
	ReponseApi<TransfertDto> modifier(@PathVariable Long id, @Valid @RequestBody TransfertRequete requete) {
		return ReponseApi.de("Transfert modifié.", service.modifier(id, requete));
	}

	@PostMapping("/{id}/expedier")
	@PreAuthorize("hasAuthority('transferts.valider')")
	ReponseApi<TransfertDto> expedier(@PathVariable Long id) {
		return ReponseApi.de("Transfert expédié : les motos sont en transit interne.", service.expedier(id));
	}

	@PostMapping("/{id}/receptionner")
	@PreAuthorize("hasAuthority('transferts.modifier')")
	ReponseApi<TransfertDto.ResultatReception> receptionner(@PathVariable Long id, @Valid @RequestBody ReceptionTransfertRequete requete) {
		return ReponseApi.de(service.receptionner(id, requete));
	}

	@PostMapping("/{id}/annuler")
	@PreAuthorize("hasAuthority('transferts.annuler')")
	ReponseApi<TransfertDto> annuler(@PathVariable Long id, @Valid @RequestBody AnnulationTransfertRequete requete) {
		return ReponseApi.de("Transfert annulé.", service.annuler(id, requete));
	}

	@GetMapping("/{id}/bon")
	@PreAuthorize("hasAuthority('transferts.voir')")
	ResponseEntity<byte[]> bon(@PathVariable Long id) {
		TransfertDto t = service.detail(id);
		return ResponseEntity.ok()
				.header("Content-Disposition", "inline; filename=\"Bon-" + t.numero() + ".pdf\"")
				.contentType(MediaType.APPLICATION_PDF)
				.body(service.bon(id));
	}

}
