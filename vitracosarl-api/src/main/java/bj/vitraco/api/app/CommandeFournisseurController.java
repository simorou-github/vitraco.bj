package bj.vitraco.api.app;

import bj.vitraco.api.common.Pagination;
import bj.vitraco.api.common.ReponseApi;
import bj.vitraco.api.common.ReponsePaginee;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/commandes-fournisseur")
class CommandeFournisseurController {

	record DemandeMotif(@NotBlank(message = "Le motif est obligatoire.") String motif) {
	}

	record DemandeAvancement(StatutCommande cible, String referenceProforma, String observation) {
	}

	private final CommandeFournisseurService service;

	CommandeFournisseurController(CommandeFournisseurService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('approvisionnements.voir')")
	ReponsePaginee<CommandeDto> lister(@RequestParam(required = false) String search,
									   @RequestParam(required = false) StatutCommande statut,
									   @RequestParam(name = "fournisseur_id", required = false) Long fournisseurId,
									   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate depuis,
									   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate jusqua,
									   @RequestParam(required = false) Integer page,
									   @RequestParam(name = "per_page", required = false) Integer parPage) {
		var filtre = new CommandeFournisseurService.Filtre(search, statut, fournisseurId, depuis, jusqua);
		return ReponsePaginee.de(service.lister(filtre, Pagination.de(page, parPage, Sort.by(Sort.Order.desc("dateCommande"), Sort.Order.desc("id")))), c -> c);
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('approvisionnements.voir')")
	ReponseApi<CommandeDto> detail(@PathVariable Long id) {
		return ReponseApi.de(service.detail(id));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAuthority('approvisionnements.creer')")
	ReponseApi<CommandeDto> creer(@Valid @RequestBody CommandeRequete requete) {
		return ReponseApi.de("Commande créée.", service.creer(requete));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('approvisionnements.modifier')")
	ReponseApi<CommandeDto> modifier(@PathVariable Long id, @Valid @RequestBody CommandeRequete requete) {
		return ReponseApi.de("Commande mise à jour.", service.modifier(id, requete));
	}

	@PostMapping("/{id}/avancer")
	@PreAuthorize("hasAuthority('approvisionnements.modifier')")
	ReponseApi<CommandeDto> avancer(@PathVariable Long id, @RequestBody(required = false) DemandeAvancement demande) {
		var d = demande == null ? new DemandeAvancement(null, null, null) : demande;
		return ReponseApi.de("Étape suivante enregistrée.", service.avancer(id, new CommandeFournisseurService.Avancement(d.cible(), d.referenceProforma(), d.observation())));
	}

	@PostMapping("/{id}/reculer")
	@PreAuthorize("hasAuthority('approvisionnements.retour_arriere')")
	ReponseApi<CommandeDto> reculer(@PathVariable Long id, @Valid @RequestBody DemandeMotif demande) {
		return ReponseApi.de("Retour à l'étape précédente enregistré.", service.reculer(id, demande.motif()));
	}

	@PostMapping("/{id}/annuler")
	@PreAuthorize("hasAuthority('approvisionnements.annuler')")
	ReponseApi<CommandeDto> annuler(@PathVariable Long id, @Valid @RequestBody DemandeMotif demande) {
		return ReponseApi.de("Commande annulée.", service.annuler(id, demande.motif()));
	}

}
