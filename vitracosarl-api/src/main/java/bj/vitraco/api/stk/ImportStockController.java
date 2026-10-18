package bj.vitraco.api.stk;

import bj.vitraco.api.common.ErreurValidationException;
import bj.vitraco.api.common.ReponseApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/** Reprise du stock : import initial depuis Excel (US-006) et rapprochement par pointage au magasin et en boutique. */
@RestController
@RequestMapping("/api/v1/stock")
class ImportStockController {

	record PointageRequete(@NotNull(message = "L'emplacement est requis.") Long emplacementId, @NotEmpty(message = "Aucun châssis n'a été pointé.") List<String> numeros) {
	}

	private final ImportStockService imports;
	private final RapprochementService rapprochements;

	ImportStockController(ImportStockService imports, RapprochementService rapprochements) {
		this.imports = imports;
		this.rapprochements = rapprochements;
	}

	@PostMapping("/imports")
	@PreAuthorize("hasAuthority('stock.importer')")
	ReponseApi<ImportStockDto.Rapport> importer(@RequestParam("fichier") MultipartFile fichier, @RequestParam(defaultValue = "false") boolean simulation) throws IOException {
		if (fichier.isEmpty()) {
			throw new ErreurValidationException("fichier", "Choisissez un fichier Excel (.xlsx) ou CSV.");
		}
		var rapport = imports.importer(fichier.getOriginalFilename(), fichier.getBytes(), simulation);
		return ReponseApi.de(simulation ? "Simulation terminée : rien n'a été créé." : "Stock initial importé.", rapport);
	}

	@GetMapping("/imports")
	@PreAuthorize("hasAuthority('stock.importer')")
	ReponseApi<List<ImportStockDto.Historique>> historique() {
		return ReponseApi.de(imports.historique());
	}

	@GetMapping("/imports/modele")
	@PreAuthorize("hasAuthority('stock.importer')")
	ResponseEntity<byte[]> modele() {
		return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"modele-import-stock-initial.xlsx\"")
				.contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(imports.modele());
	}

	@GetMapping("/imports/{id}")
	@PreAuthorize("hasAuthority('stock.importer')")
	ReponseApi<ImportStockDto.Rapport> rapport(@PathVariable Long id) {
		return ReponseApi.de(imports.rapport(id));
	}

	@PostMapping("/rapprochements")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAuthority('inventaires.creer')")
	ReponseApi<ImportStockDto.Rapprochement> pointer(@Valid @RequestBody PointageRequete requete) {
		return ReponseApi.de("Rapprochement enregistré.", rapprochements.comparer(requete.emplacementId(), requete.numeros()));
	}

	@GetMapping("/rapprochements")
	@PreAuthorize("hasAuthority('inventaires.voir')")
	ReponseApi<List<ImportStockDto.RapprochementListe>> rapprochements(@RequestParam(name = "emplacement_id", required = false) Long emplacementId) {
		return ReponseApi.de(rapprochements.lister(emplacementId));
	}

	@GetMapping("/rapprochements/{id}")
	@PreAuthorize("hasAuthority('inventaires.voir')")
	ReponseApi<ImportStockDto.Rapprochement> rapprochement(@PathVariable Long id) {
		return ReponseApi.de(rapprochements.detail(id));
	}

}
