package bj.vitraco.api.cat.chassis;

import bj.vitraco.api.common.Pagination;
import bj.vitraco.api.common.ReponseApi;
import bj.vitraco.api.common.ReponsePaginee;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Sort;
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

import java.nio.charset.StandardCharsets;

/** Les châssis se lisent ici ; ils se créent par l'import du colisage et la réception, jamais à la main (SRS 3.4). */
@RestController
@RequestMapping("/api/v1/chassis")
class ChassisController {

	record DemandeCorrection(@NotBlank(message = "Le nouveau numéro est requis.") String nouveauNumero,
							 @NotBlank(message = "Le motif est obligatoire.") String motif) {
	}

	record DemandeRefus(@NotBlank(message = "Le motif du refus est obligatoire.") String motif) {
	}

	private final ChassisConsultation consultation;
	private final CorrectionNumeroService corrections;

	ChassisController(ChassisConsultation consultation, CorrectionNumeroService corrections) {
		this.consultation = consultation;
		this.corrections = corrections;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('stock.voir')")
	ReponsePaginee<ChassisDto> lister(@RequestParam(required = false) String search,
									  @RequestParam(required = false) StatutChassis statut,
									  @RequestParam(name = "modele_id", required = false) Long modeleId,
									  @RequestParam(name = "emplacement_id", required = false) Long emplacementId,
									  @RequestParam(name = "arrivage_id", required = false) Long arrivageId,
									  @RequestParam(name = "anciennete_min", required = false) Integer ancienneteMin,
									  @RequestParam(required = false) Integer page,
									  @RequestParam(name = "per_page", required = false) Integer parPage) {
		var filtre = new ChassisConsultation.Filtre(search, statut, modeleId, emplacementId, arrivageId, ancienneteMin);
		return ReponsePaginee.de(consultation.lister(filtre, Pagination.de(page, parPage, Sort.by(Sort.Order.desc("dateEntree").nullsLast(), Sort.Order.asc("numero")))), c -> c);
	}

	@GetMapping("/export")
	@PreAuthorize("hasAuthority('stock.exporter')")
	ResponseEntity<byte[]> exporter(@RequestParam(required = false) String search,
									@RequestParam(required = false) StatutChassis statut,
									@RequestParam(name = "modele_id", required = false) Long modeleId,
									@RequestParam(name = "emplacement_id", required = false) Long emplacementId,
									@RequestParam(name = "arrivage_id", required = false) Long arrivageId,
									@RequestParam(name = "anciennete_min", required = false) Integer ancienneteMin) {
		String csv = consultation.exporter(new ChassisConsultation.Filtre(search, statut, modeleId, emplacementId, arrivageId, ancienneteMin));
		return ResponseEntity.ok()
				.header("Content-Disposition", "attachment; filename=\"chassis.csv\"")
				.contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
				.body(csv.getBytes(StandardCharsets.UTF_8));
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('stock.voir')")
	ReponseApi<ChassisFicheDto> fiche(@PathVariable Long id) {
		return ReponseApi.de(consultation.fiche(id));
	}

	@PostMapping("/{id}/corrections")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAuthority('stock.modifier')")
	ReponseApi<CorrectionNumeroService.Correction> demanderCorrection(@PathVariable Long id, @Valid @RequestBody DemandeCorrection demande) {
		return ReponseApi.de("Correction demandée. Un autre utilisateur doit la valider.", corrections.demander(id, demande.nouveauNumero(), demande.motif()));
	}

	@PostMapping("/corrections/{id}/valider")
	@PreAuthorize("hasAuthority('stock.modifier')")
	ReponseApi<CorrectionNumeroService.Correction> validerCorrection(@PathVariable Long id) {
		return ReponseApi.de("Correction validée.", corrections.valider(id));
	}

	@PostMapping("/corrections/{id}/refuser")
	@PreAuthorize("hasAuthority('stock.modifier')")
	ReponseApi<CorrectionNumeroService.Correction> refuserCorrection(@PathVariable Long id, @Valid @RequestBody DemandeRefus demande) {
		return ReponseApi.de("Correction refusée.", corrections.refuser(id, demande.motif()));
	}

}
