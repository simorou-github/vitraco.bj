package bj.vitraco.api.adm.referentiels;

import bj.vitraco.api.adm.utilisateurs.UtilisateurRepository;
import bj.vitraco.api.aud.EntreeAudit;
import bj.vitraco.api.aud.JournalAudit;
import bj.vitraco.api.common.CodeModule;
import bj.vitraco.api.common.ErreurMetierException;
import bj.vitraco.api.common.ErreurValidationException;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
class ServiceFournisseurs {

	private final FournisseurRepository fournisseurs;
	private final UtilisateurRepository utilisateurs;
	private final JournalAudit journal;

	ServiceFournisseurs(FournisseurRepository fournisseurs, UtilisateurRepository utilisateurs, JournalAudit journal) {
		this.fournisseurs = fournisseurs;
		this.utilisateurs = utilisateurs;
		this.journal = journal;
	}

	@Transactional(readOnly = true)
	Page<FournisseurDto> lister(String recherche, Boolean actif, Pageable pagination) {
		Specification<Fournisseur> spec = (racine, requete, cb) -> {
			List<Predicate> criteres = new ArrayList<>();
			if (recherche != null && !recherche.isBlank()) {
				String motif = "%" + recherche.trim().toLowerCase(Locale.ROOT) + "%";
				criteres.add(cb.or(cb.like(cb.lower(racine.get("raisonSociale")), motif), cb.like(cb.lower(racine.get("pays")), motif)));
			}
			if (actif != null) {
				criteres.add(cb.equal(racine.get("actif"), actif));
			}
			return cb.and(criteres.toArray(new Predicate[0]));
		};
		return fournisseurs.findAll(spec, pagination).map(FournisseurDto::de);
	}

	@Transactional(readOnly = true)
	FournisseurDto detail(Long id) {
		return FournisseurDto.de(trouver(id));
	}

	@Transactional
	FournisseurDto creer(FournisseurRequete requete) {
		String raisonSociale = requete.raisonSociale().trim();
		if (fournisseurs.raisonSocialeUtilisee(raisonSociale, null)) {
			throw new ErreurValidationException("raison_sociale", "Un fournisseur porte déjà cette raison sociale.");
		}
		Fournisseur f = new Fournisseur(raisonSociale, requete.pays().trim(), requete.devise().trim().toUpperCase(Locale.ROOT));
		remplacerContacts(f, requete);
		f.setCreePar(Acteurs.courant(utilisateurs));
		fournisseurs.saveAndFlush(f);
		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, "fournisseur.cree", "Fournisseur", String.valueOf(f.getId()))
				.avecValeurs(null, resume(f)));
		return FournisseurDto.de(f);
	}

	@Transactional
	FournisseurDto modifier(Long id, FournisseurRequete requete) {
		Fournisseur f = trouver(id);
		String raisonSociale = requete.raisonSociale().trim();
		if (fournisseurs.raisonSocialeUtilisee(raisonSociale, id)) {
			throw new ErreurValidationException("raison_sociale", "Un fournisseur porte déjà cette raison sociale.");
		}
		Map<String, Object> avant = resume(f);
		f.setRaisonSociale(raisonSociale);
		f.setPays(requete.pays().trim());
		f.setDevise(requete.devise().trim().toUpperCase(Locale.ROOT));
		remplacerContacts(f, requete);
		f.setModifiePar(Acteurs.courant(utilisateurs));
		fournisseurs.saveAndFlush(f);
		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, "fournisseur.modifie", "Fournisseur", String.valueOf(id))
				.avecValeurs(avant, resume(f)));
		return FournisseurDto.de(f);
	}

	@Transactional
	FournisseurDto desactiver(Long id) {
		return changerEtat(id, false);
	}

	@Transactional
	FournisseurDto reactiver(Long id) {
		return changerEtat(id, true);
	}

	private FournisseurDto changerEtat(Long id, boolean actif) {
		Fournisseur f = trouver(id);
		if (f.isActif() == actif) {
			throw new ErreurMetierException(actif ? "DEJA_ACTIF" : "DEJA_DESACTIVE",
					actif ? "Ce fournisseur est déjà actif." : "Ce fournisseur est déjà désactivé.");
		}
		f.setActif(actif);
		f.setModifiePar(Acteurs.courant(utilisateurs));
		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, actif ? "fournisseur.reactive" : "fournisseur.desactive", "Fournisseur", String.valueOf(id))
				.avecValeurs(Map.of("actif", !actif), Map.of("actif", actif)));
		return FournisseurDto.de(f);
	}

	private static void remplacerContacts(Fournisseur f, FournisseurRequete requete) {
		f.getContacts().clear();
		if (requete.contacts() != null) {
			requete.contacts().forEach(c -> f.getContacts().add(
					new ContactFournisseur(c.nom().trim(), Acteurs.vide(c.fonction()), Acteurs.vide(c.email()), Acteurs.vide(c.telephone()))));
		}
	}

	private Fournisseur trouver(Long id) {
		return fournisseurs.findById(id)
				.orElseThrow(() -> new ErreurMetierException("INTROUVABLE", "Fournisseur introuvable.", HttpStatus.NOT_FOUND));
	}

	private static Map<String, Object> resume(Fournisseur f) {
		Map<String, Object> valeurs = new LinkedHashMap<>();
		valeurs.put("raison_sociale", f.getRaisonSociale());
		valeurs.put("pays", f.getPays());
		valeurs.put("devise", f.getDevise());
		valeurs.put("contacts", f.getContacts().stream().map(ContactFournisseur::getNom).toList());
		valeurs.put("actif", f.isActif());
		return valeurs;
	}

}
