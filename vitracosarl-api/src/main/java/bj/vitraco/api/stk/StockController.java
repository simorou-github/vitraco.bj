package bj.vitraco.api.stk;

import bj.vitraco.api.common.Pagination;
import bj.vitraco.api.common.ReponseApi;
import bj.vitraco.api.common.ReponsePaginee;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/stock")
class StockController {

	private final StockService service;

	StockController(StockService service) {
		this.service = service;
	}

	@GetMapping("/synthese")
	@PreAuthorize("hasAuthority('stock.voir')")
	ReponseApi<StockDto.Synthese> synthese(@RequestParam(required = false) String type, @RequestParam(name = "modele_id", required = false) Long modeleId) {
		return ReponseApi.de(service.synthese(type, modeleId));
	}

	@GetMapping("/modeles")
	@PreAuthorize("hasAuthority('stock.voir')")
	ReponseApi<List<StockDto.LigneModele>> parModele(@RequestParam(name = "emplacement_id", required = false) Long emplacementId,
													 @RequestParam(required = false) String type, @RequestParam(required = false) String search) {
		return ReponseApi.de(service.parModele(emplacementId, type, search));
	}

	@GetMapping("/bloques")
	@PreAuthorize("hasAuthority('stock.voir')")
	ReponsePaginee<StockDto.Bloque> bloques(@RequestParam(name = "emplacement_id", required = false) Long emplacementId, @RequestParam(required = false) String search,
											@RequestParam(required = false) Integer page, @RequestParam(name = "per_page", required = false) Integer parPage) {
		return ReponsePaginee.de(service.bloques(emplacementId, search, Pagination.de(page, parPage, Sort.unsorted())), b -> b);
	}

	@GetMapping("/mouvements")
	@PreAuthorize("hasAuthority('stock.voir')")
	ReponsePaginee<StockDto.Mouvement> mouvements(@RequestParam(required = false) String search, @RequestParam(name = "modele_id", required = false) Long modeleId,
												  @RequestParam(name = "emplacement_id", required = false) Long emplacementId, @RequestParam(required = false) String type,
												  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
												  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au,
												  @RequestParam(required = false) Integer page, @RequestParam(name = "per_page", required = false) Integer parPage) {
		return ReponsePaginee.de(service.mouvements(new StockService.FiltreMouvements(search, modeleId, emplacementId, type, du, au),
				Pagination.de(page, parPage, Sort.unsorted())), m -> m);
	}

	@PostMapping("/chassis/{id}/bloquer")
	@PreAuthorize("hasAuthority('stock.bloquer')")
	ReponseApi<Void> bloquer(@PathVariable Long id, @Valid @RequestBody BlocageRequete requete) {
		service.bloquer(id, requete);
		return ReponseApi.de("Châssis bloqué.", null);
	}

	@PostMapping("/chassis/{id}/debloquer")
	@PreAuthorize("hasAuthority('stock.bloquer')")
	ReponseApi<Void> debloquer(@PathVariable Long id, @Valid @RequestBody DeblocageRequete requete) {
		service.debloquer(id, requete);
		return ReponseApi.de("Châssis débloqué.", null);
	}

}
