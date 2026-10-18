package bj.vitraco.api.app;

import bj.vitraco.api.common.ErreurValidationException;
import bj.vitraco.api.common.Pagination;
import bj.vitraco.api.common.ReponseApi;
import bj.vitraco.api.common.ReponsePaginee;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1")
class ArrivageController {

	record DemandeDouane(StatutDouane statut) {
	}

	record DemandeMotif(@NotBlank(message = "Le motif est obligatoire.") String motif) {
	}

	private final ArrivageService service;
	private final ColisageService colisage;

	ArrivageController(ArrivageService service, ColisageService colisage) {
		this.service = service;
		this.colisage = colisage;
	}

	@GetMapping("/arrivages")
	@PreAuthorize("hasAuthority('approvisionnements.voir')")
	ReponsePaginee<ArrivageDto> lister(@RequestParam(name = "commande_id", required = false) Long commandeId,
									   @RequestParam(name = "en_cours", required = false) Boolean enCours,
									   @RequestParam(required = false) String search,
									   @RequestParam(required = false) Integer page,
									   @RequestParam(name = "per_page", required = false) Integer parPage) {
		var pagination = Pagination.de(page, parPage, Sort.by(Sort.Order.asc("dateArriveePrevue").nullsLast(), Sort.Order.desc("id")));
		return ReponsePaginee.de(service.lister(new ArrivageService.Filtre(commandeId, enCours, search), pagination), a -> a);
	}

	@GetMapping("/arrivages/{id}")
	@PreAuthorize("hasAuthority('approvisionnements.voir')")
	ReponseApi<ArrivageDto> detail(@PathVariable Long id) {
		return ReponseApi.de(service.detail(id));
	}

	@PostMapping("/commandes-fournisseur/{commandeId}/arrivages")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAuthority('approvisionnements.modifier')")
	ReponseApi<ArrivageDto> creer(@PathVariable Long commandeId, @Valid @RequestBody ArrivageRequete requete) {
		return ReponseApi.de("Arrivage créé.", service.creer(commandeId, requete));
	}

	@PutMapping("/arrivages/{id}")
	@PreAuthorize("hasAuthority('approvisionnements.modifier')")
	ReponseApi<ArrivageDto> modifier(@PathVariable Long id, @Valid @RequestBody ArrivageRequete requete) {
		return ReponseApi.de("Arrivage mis à jour.", service.modifier(id, requete));
	}

	@PostMapping("/arrivages/{id}/douane")
	@PreAuthorize("hasAuthority('approvisionnements.modifier')")
	ReponseApi<ArrivageDto> avancerDouane(@PathVariable Long id, @RequestBody(required = false) DemandeDouane demande) {
		return ReponseApi.de("Dédouanement avancé.", service.avancerDouane(id, demande == null ? null : demande.statut()));
	}

	@PostMapping("/arrivages/{id}/frais")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAuthority('approvisionnements.modifier')")
	ReponseApi<ArrivageDto> ajouterFrais(@PathVariable Long id, @Valid @RequestBody FraisRequete requete) {
		return ReponseApi.de("Frais enregistré.", service.ajouterFrais(id, requete));
	}

	@PutMapping("/arrivages/{id}/frais/{fraisId}")
	@PreAuthorize("hasAuthority('approvisionnements.modifier')")
	ReponseApi<ArrivageDto> modifierFrais(@PathVariable Long id, @PathVariable Long fraisId, @Valid @RequestBody FraisRequete requete) {
		return ReponseApi.de("Frais mis à jour.", service.modifierFrais(id, fraisId, requete));
	}

	@PostMapping("/arrivages/{id}/frais/{fraisId}/annuler")
	@PreAuthorize("hasAuthority('approvisionnements.modifier')")
	ReponseApi<ArrivageDto> annulerFrais(@PathVariable Long id, @PathVariable Long fraisId, @Valid @RequestBody DemandeMotif demande) {
		return ReponseApi.de("Frais annulé.", service.annulerFrais(id, fraisId, demande.motif()));
	}

	@PostMapping("/arrivages/{id}/colisage")
	@PreAuthorize("hasAuthority('approvisionnements.modifier')")
	ReponseApi<ColisageService.RapportColisage> importerColisage(@PathVariable Long id, @RequestParam("fichier") MultipartFile fichier,
																   @RequestParam(defaultValue = "false") boolean simulation) throws IOException {
		if (fichier.isEmpty()) {
			throw new ErreurValidationException("fichier", "Choisissez un fichier CSV ou Excel (.xlsx).");
		}
		var rapport = colisage.importer(id, fichier.getOriginalFilename(), fichier.getBytes(), simulation);
		return ReponseApi.de(simulation ? "Simulation terminée : rien n'a été créé." : "Colisage importé.", rapport);
	}

}
