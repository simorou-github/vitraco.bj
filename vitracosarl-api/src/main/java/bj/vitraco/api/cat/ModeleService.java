package bj.vitraco.api.cat;

import bj.vitraco.api.adm.utilisateurs.ActeurDto;
import bj.vitraco.api.adm.utilisateurs.Utilisateur;
import bj.vitraco.api.adm.utilisateurs.UtilisateurRepository;
import bj.vitraco.api.aud.EntreeAudit;
import bj.vitraco.api.aud.JournalAudit;
import bj.vitraco.api.cat.PrixService.ChangementPrix;
import bj.vitraco.api.cat.PrixService.PrixEnVigueur;
import bj.vitraco.api.common.CodeModule;
import bj.vitraco.api.common.ErreurMetierException;
import bj.vitraco.api.common.ErreurValidationException;
import bj.vitraco.api.common.security.ActeurAuthentifie;
import bj.vitraco.api.common.security.Droits;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Catalogue des modèles (EF-CAT-01) et changements de prix datés (EF-CAT-02). Un modèle ne se
 * supprime pas : il se désactive, et son stock reste visible (EF-CAT-08, RG-18).
 */
@Service
public class ModeleService {

	static final String PERMISSION_PRIX_ACHAT = "prix_achat.voir";
	static final String PERMISSION_VALIDATION = "catalogue.valider";

	public record Filtre(String recherche, String marque, Boolean actif) {
	}

	private final ModeleRepository modeles;
	private final UtilisateurRepository utilisateurs;
	private final PrixService prix;
	private final JournalAudit journal;
	private final Clock horloge;

	ModeleService(ModeleRepository modeles, UtilisateurRepository utilisateurs, PrixService prix, JournalAudit journal, Clock horloge) {
		this.modeles = modeles;
		this.utilisateurs = utilisateurs;
		this.prix = prix;
		this.journal = journal;
		this.horloge = horloge;
	}

	@Transactional(readOnly = true)
	public Page<ModeleDto> lister(Filtre filtre, Pageable pagination) {
		Specification<Modele> spec = (racine, requete, cb) -> {
			List<Predicate> criteres = new ArrayList<>();
			if (filtre.recherche() != null && !filtre.recherche().isBlank()) {
				String motif = "%" + filtre.recherche().trim().toLowerCase(Locale.ROOT) + "%";
				criteres.add(cb.or(cb.like(cb.lower(racine.get("codeArticle")), motif), cb.like(cb.lower(racine.get("marque")), motif),
						cb.like(cb.lower(racine.get("nom")), motif), cb.like(cb.lower(cb.coalesce(racine.get("versionModele"), "")), motif)));
			}
			if (filtre.marque() != null && !filtre.marque().isBlank()) {
				criteres.add(cb.equal(cb.lower(racine.get("marque")), filtre.marque().trim().toLowerCase(Locale.ROOT)));
			}
			if (filtre.actif() != null) {
				criteres.add(cb.equal(racine.get("actif"), filtre.actif()));
			}
			return cb.and(criteres.toArray(new Predicate[0]));
		};
		Page<Modele> page = modeles.findAll(spec, pagination);
		Map<Long, PrixEnVigueur> prixEnVigueur = prix.enVigueur(page.getContent().stream().map(Modele::getId).toList(), LocalDate.now(horloge));
		return page.map(m -> versDto(m, prixEnVigueur.getOrDefault(m.getId(), new PrixEnVigueur(null, null, null)), List.of()));
	}

	@Transactional(readOnly = true)
	public ModeleDto detail(Long id) {
		return detailDe(trouver(id));
	}

	@Transactional(readOnly = true)
	public List<String> marques() {
		return modeles.findAll().stream().map(Modele::getMarque).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
	}

	@Transactional(readOnly = true)
	public List<PrixService.LignePrix> historique(Long id) {
		trouver(id);
		boolean achatVisible = Droits.aPermission(PERMISSION_PRIX_ACHAT);
		return prix.historique(id).stream().filter(l -> achatVisible || l.type() != TypePrix.ACHAT_REFERENCE).toList();
	}

	@Transactional
	public ModeleDto creer(ModeleRequete requete) {
		String code = requete.codeArticle().trim().toUpperCase(Locale.ROOT);
		if (modeles.codeArticleUtilise(code)) {
			throw new ErreurValidationException("code_article", "Ce code article est déjà utilisé.");
		}
		if (requete.prixMinimum() > requete.prixVente()) {
			throw new ErreurValidationException("prix_minimum", "Le prix minimum ne peut pas dépasser le prix de vente.");
		}
		exigerDroitPrixAchat(requete.prixAchatReference() != null);

		Modele m = new Modele(requete.marque().trim(), requete.modele().trim(), code);
		m.setVersionModele(vide(requete.version()));
		m.setDescription(vide(requete.description()));
		m.setGroupeTaxation(groupe(requete.groupeTaxation()));
		m.setFormatChassis(format(requete.formatChassis()));
		Utilisateur acteur = acteur();
		m.setCreePar(acteur);
		modeles.saveAndFlush(m);

		LocalDate aujourdhui = LocalDate.now(horloge);
		Long auteur = acteur == null ? null : acteur.getId();
		prix.enregistrer(m.getId(), new ChangementPrix(TypePrix.VENTE, requete.prixVente()), aujourdhui, "Prix initial", auteur);
		prix.enregistrer(m.getId(), new ChangementPrix(TypePrix.MINIMUM, requete.prixMinimum()), aujourdhui, "Prix initial", auteur);
		if (requete.prixAchatReference() != null) {
			prix.enregistrer(m.getId(), new ChangementPrix(TypePrix.ACHAT_REFERENCE, requete.prixAchatReference()), aujourdhui, "Prix initial", auteur);
		}

		Map<String, Object> apres = resume(m);
		apres.put("prix_vente", requete.prixVente());
		apres.put("prix_minimum", requete.prixMinimum());
		journal.enregistrer(EntreeAudit.de(CodeModule.CAT, "modele.cree", "Modele", String.valueOf(m.getId())).avecValeurs(null, apres));
		return detailDe(m);
	}

	@Transactional
	public ModeleDto modifier(Long id, ModeleModification requete) {
		Modele m = trouver(id);
		Map<String, Object> avant = resume(m);
		m.setMarque(requete.marque().trim());
		m.setNom(requete.modele().trim());
		m.setVersionModele(vide(requete.version()));
		m.setDescription(vide(requete.description()));
		m.setGroupeTaxation(groupe(requete.groupeTaxation()));
		m.setFormatChassis(format(requete.formatChassis()));
		m.setModifiePar(acteur());
		modeles.saveAndFlush(m);
		journal.enregistrer(EntreeAudit.de(CodeModule.CAT, "modele.modifie", "Modele", String.valueOf(id)).avecValeurs(avant, resume(m)));
		return detailDe(m);
	}

	@Transactional
	public ModeleDto desactiver(Long id) {
		return changerEtat(id, false);
	}

	@Transactional
	public ModeleDto reactiver(Long id) {
		return changerEtat(id, true);
	}

	private ModeleDto changerEtat(Long id, boolean actif) {
		Modele m = trouver(id);
		if (m.isActif() == actif) {
			throw new ErreurMetierException(actif ? "DEJA_ACTIF" : "DEJA_DESACTIVE",
					actif ? "Ce modèle est déjà actif." : "Ce modèle est déjà désactivé.");
		}
		m.setActif(actif);
		m.setModifiePar(acteur());
		journal.enregistrer(EntreeAudit.de(CodeModule.CAT, actif ? "modele.reactive" : "modele.desactive", "Modele", String.valueOf(id))
				.avecValeurs(Map.of("actif", !actif), Map.of("actif", actif)));
		return detailDe(m);
	}

	/**
	 * Change un ou plusieurs prix à une date d'effet (US-011). Le prix minimum ne dépasse jamais le prix de vente, à
	 * aucune date. Baisser le prix minimum, donc vendre au-dessous du plancher actuel, exige la validation de la
	 * Direction (permission « catalogue.valider »).
	 */
	@Transactional
	public ModeleDto changerPrix(Long id, ChangementPrixRequete requete) {
		Modele m = trouver(id);
		LocalDate aujourdhui = LocalDate.now(horloge);
		LocalDate dateEffet = requete.dateEffet() == null ? aujourdhui : requete.dateEffet();
		if (dateEffet.isBefore(aujourdhui)) {
			throw new ErreurValidationException("date_effet", "La date d'effet ne peut pas être dans le passé : l'historique ne se réécrit pas.");
		}
		if (requete.prixVente() == null && requete.prixMinimum() == null && requete.prixAchatReference() == null) {
			throw new ErreurValidationException("prix_vente", "Indiquez au moins un prix à changer.");
		}
		exigerDroitPrixAchat(requete.prixAchatReference() != null);

		PrixEnVigueur avant = prix.enVigueur(List.of(id), dateEffet).getOrDefault(id, new PrixEnVigueur(null, null, null));
		List<ChangementPrix> changements = new ArrayList<>();
		ajouterSiDifferent(changements, TypePrix.VENTE, requete.prixVente(), avant.vente());
		ajouterSiDifferent(changements, TypePrix.MINIMUM, requete.prixMinimum(), avant.minimum());
		ajouterSiDifferent(changements, TypePrix.ACHAT_REFERENCE, requete.prixAchatReference(), avant.achatReference());
		if (changements.isEmpty()) {
			throw new ErreurMetierException("PRIX_INCHANGE", "Ces prix sont déjà en vigueur à cette date.");
		}

		Long vente = requete.prixVente() != null ? requete.prixVente() : avant.vente();
		Long minimum = requete.prixMinimum() != null ? requete.prixMinimum() : avant.minimum();
		if (vente != null && minimum != null && minimum > vente) {
			throw new ErreurValidationException("prix_minimum", "Le prix minimum ne peut pas dépasser le prix de vente.");
		}
		prix.premiereIncoherence(id, dateEffet, changements).ifPresent(date -> {
			throw new ErreurValidationException("prix_minimum",
					"Ce changement rendrait le prix minimum supérieur au prix de vente au " + date + " : ajustez aussi le prix déjà programmé.");
		});
		if (requete.prixMinimum() != null && avant.minimum() != null && requete.prixMinimum() < avant.minimum()
				&& !Droits.aPermission(PERMISSION_VALIDATION)) {
			throw new ErreurMetierException("VALIDATION_DIRECTION_REQUISE",
					"Baisser le prix minimum, c'est autoriser une vente sous le plancher actuel : cela exige la validation de la Direction.",
					HttpStatus.FORBIDDEN);
		}

		Utilisateur acteur = acteur();
		Long auteur = acteur == null ? null : acteur.getId();
		Map<String, Object> ancien = new LinkedHashMap<>();
		Map<String, Object> nouveau = new LinkedHashMap<>();
		for (ChangementPrix c : changements) {
			prix.enregistrer(id, c, dateEffet, vide(requete.motif()), auteur);
			ancien.put(c.type().name().toLowerCase(Locale.ROOT), valeurDe(avant, c.type()));
			nouveau.put(c.type().name().toLowerCase(Locale.ROOT), c.valeur());
		}
		nouveau.put("date_effet", dateEffet.toString());
		journal.enregistrer(EntreeAudit.de(CodeModule.CAT, "modele.prix_modifie", "Modele", String.valueOf(id))
				.avecValeurs(ancien, nouveau).avecMotif(vide(requete.motif())));
		return detailDe(m);
	}

	private static void ajouterSiDifferent(List<ChangementPrix> changements, TypePrix type, Long demande, Long courant) {
		if (demande != null && !demande.equals(courant)) {
			changements.add(new ChangementPrix(type, demande));
		}
	}

	private static Long valeurDe(PrixEnVigueur p, TypePrix type) {
		return switch (type) {
			case VENTE -> p.vente();
			case MINIMUM -> p.minimum();
			case ACHAT_REFERENCE -> p.achatReference();
		};
	}

	private void exigerDroitPrixAchat(boolean concerne) {
		if (concerne && !Droits.aPermission(PERMISSION_PRIX_ACHAT)) {
			throw new ErreurMetierException("ACCES_REFUSE", "Vous n'avez pas le droit de voir ni de modifier les prix d'achat.", HttpStatus.FORBIDDEN);
		}
	}

	private ModeleDto detailDe(Modele m) {
		LocalDate aujourdhui = LocalDate.now(horloge);
		PrixEnVigueur courant = prix.enVigueur(List.of(m.getId()), aujourdhui).getOrDefault(m.getId(), new PrixEnVigueur(null, null, null));
		boolean achatVisible = Droits.aPermission(PERMISSION_PRIX_ACHAT);
		List<ModeleDto.PrixProgramme> programmes = prix.historique(m.getId()).stream()
				.filter(l -> l.dateEffet().isAfter(aujourdhui) && (achatVisible || l.type() != TypePrix.ACHAT_REFERENCE))
				.map(l -> new ModeleDto.PrixProgramme(l.type(), l.valeur(), l.dateEffet()))
				.sorted(java.util.Comparator.comparing(ModeleDto.PrixProgramme::dateEffet)).toList();
		return versDto(m, courant, programmes);
	}

	private static ModeleDto versDto(Modele m, PrixEnVigueur p, List<ModeleDto.PrixProgramme> programmes) {
		boolean achatVisible = Droits.aPermission(PERMISSION_PRIX_ACHAT);
		return new ModeleDto(m.getId(), m.getMarque(), m.getNom(), m.getVersionModele(), m.getCodeArticle(), m.getDescription(),
				m.getGroupeTaxation(), m.getFormatChassis(), m.isActif(), p.vente(), p.minimum(),
				achatVisible ? p.achatReference() : null, achatVisible, programmes, m.getCreeLe(), ActeurDto.de(m.getCreePar()));
	}

	private Modele trouver(Long id) {
		return modeles.findById(id)
				.orElseThrow(() -> new ErreurMetierException("MODELE_INTROUVABLE", "Modèle introuvable.", HttpStatus.NOT_FOUND));
	}

	private Utilisateur acteur() {
		return ActeurAuthentifie.courant().flatMap(a -> utilisateurs.findById(a.id())).orElse(null);
	}

	private static String groupe(String valeur) {
		return valeur == null || valeur.isBlank() ? null : valeur.trim().toUpperCase(Locale.ROOT);
	}

	/** Le format est une expression régulière : on la compile tout de suite pour ne pas la découvrir cassée à la réception. */
	private static String format(String valeur) {
		String v = vide(valeur);
		if (v != null) {
			try {
				Pattern.compile(v);
			} catch (PatternSyntaxException e) {
				throw new ErreurValidationException("format_chassis", "Expression régulière invalide : " + e.getDescription() + ".");
			}
		}
		return v;
	}

	private static String vide(String valeur) {
		return valeur == null || valeur.isBlank() ? null : valeur.trim();
	}

	private static Map<String, Object> resume(Modele m) {
		Map<String, Object> valeurs = new LinkedHashMap<>();
		valeurs.put("marque", m.getMarque());
		valeurs.put("modele", m.getNom());
		valeurs.put("version", m.getVersionModele());
		valeurs.put("code_article", m.getCodeArticle());
		valeurs.put("description", m.getDescription());
		valeurs.put("groupe_taxation", m.getGroupeTaxation());
		valeurs.put("format_chassis", m.getFormatChassis());
		valeurs.put("actif", m.isActif());
		return valeurs;
	}

}
