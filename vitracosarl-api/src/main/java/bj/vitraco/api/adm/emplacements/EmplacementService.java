package bj.vitraco.api.adm.emplacements;

import bj.vitraco.api.adm.utilisateurs.Utilisateur;
import bj.vitraco.api.adm.utilisateurs.UtilisateurRepository;
import bj.vitraco.api.aud.EntreeAudit;
import bj.vitraco.api.aud.JournalAudit;
import bj.vitraco.api.common.CodeModule;
import bj.vitraco.api.common.ErreurMetierException;
import bj.vitraco.api.common.ErreurValidationException;
import bj.vitraco.api.common.security.ActeurAuthentifie;
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
import java.util.Objects;

/**
 * Magasins, boutiques et dépôts (EF-ADM-01). Un emplacement déjà utilisé ne se supprime pas : il
 * se désactive (EF-ADM-08), et seulement s'il n'a plus ni stock ni opération en cours.
 */
@Service
public class EmplacementService {

	public record Filtre(String recherche, TypeEmplacement type, Boolean actif) {
	}

	private final EmplacementRepository emplacements;
	private final UtilisateurRepository utilisateurs;
	private final List<VerificationUsageEmplacement> usages;
	private final JournalAudit journal;

	EmplacementService(EmplacementRepository emplacements, UtilisateurRepository utilisateurs,
					   List<VerificationUsageEmplacement> usages, JournalAudit journal) {
		this.emplacements = emplacements;
		this.utilisateurs = utilisateurs;
		this.usages = usages;
		this.journal = journal;
	}

	@Transactional(readOnly = true)
	public Page<EmplacementDto> lister(Filtre filtre, Pageable pagination) {
		Specification<Emplacement> spec = (racine, requete, cb) -> {
			List<Predicate> criteres = new ArrayList<>();
			if (filtre.recherche() != null && !filtre.recherche().isBlank()) {
				String motif = "%" + filtre.recherche().trim().toLowerCase(Locale.ROOT) + "%";
				criteres.add(cb.or(cb.like(cb.lower(racine.get("code")), motif), cb.like(cb.lower(racine.get("nom")), motif)));
			}
			if (filtre.type() != null) {
				criteres.add(cb.equal(racine.get("type"), filtre.type()));
			}
			if (filtre.actif() != null) {
				criteres.add(cb.equal(racine.get("actif"), filtre.actif()));
			}
			return cb.and(criteres.toArray(new Predicate[0]));
		};
		return emplacements.findAll(spec, pagination).map(EmplacementDto::de);
	}

	/** Emplacements actifs, en version courte, pour les listes de choix de tous les écrans (sans les coordonnées du responsable). */
	@Transactional(readOnly = true)
	public List<EmplacementRef> choix(TypeEmplacement type) {
		return emplacements.findByActifTrue().stream()
				.filter(e -> type == null || e.getType() == type)
				.sorted(java.util.Comparator.comparing(Emplacement::getCode))
				.map(EmplacementRef::de).toList();
	}

	@Transactional(readOnly = true)
	public EmplacementDto detail(Long id) {
		return EmplacementDto.de(trouver(id));
	}

	@Transactional
	public EmplacementDto creer(EmplacementRequete requete) {
		if (requete.type() != TypeEmplacement.MAGASIN && requete.type() != TypeEmplacement.BOUTIQUE) {
			throw new ErreurValidationException("type",
					"Seuls un magasin ou une boutique se créent ici : les dépôts chez un revendeur et le transit sont gérés par le système.");
		}
		String code = requete.code().trim().toUpperCase(Locale.ROOT);
		if (emplacements.codeUtilise(code)) {
			throw new ErreurValidationException("code", "Ce code est déjà utilisé.");
		}

		Emplacement e = new Emplacement(code, requete.nom().trim(), requete.type(), false);
		appliquer(e, requete);
		e.setCreePar(acteur());
		emplacements.saveAndFlush(e);

		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, "emplacement.cree", "Emplacement", String.valueOf(e.getId()))
				.avecValeurs(null, resume(e)));
		return EmplacementDto.de(e);
	}

	@Transactional
	public EmplacementDto modifier(Long id, EmplacementRequete requete) {
		Emplacement e = trouver(id);
		interdireSiSysteme(e);
		if (!e.getCode().equalsIgnoreCase(requete.code().trim())) {
			throw new ErreurValidationException("code", "Le code d'un emplacement ne peut pas être modifié.");
		}
		if (e.getType() != requete.type()) {
			throw new ErreurValidationException("type", "Le type d'un emplacement ne peut pas être modifié.");
		}
		Map<String, Object> avant = resume(e);

		boolean modeOuRattachementChange = e.getType() == TypeEmplacement.BOUTIQUE
				&& (e.getModeBoutique() != requete.modeBoutique()
				|| !Objects.equals(idRattachement(e), requete.magasinRattachementId()));
		if (modeOuRattachementChange && enUsage(id)) {
			throw new ErreurMetierException("EMPLACEMENT_MODE_BLOQUE",
					"Le mode et le rattachement d'une boutique ne changent pas tant qu'elle a du stock ou une opération en cours.");
		}

		e.setNom(requete.nom().trim());
		appliquer(e, requete);
		e.setModifiePar(acteur());
		emplacements.saveAndFlush(e);

		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, "emplacement.modifie", "Emplacement", String.valueOf(id))
				.avecValeurs(avant, resume(e)));
		return EmplacementDto.de(e);
	}

	@Transactional
	public EmplacementDto desactiver(Long id) {
		Emplacement e = trouver(id);
		interdireSiSysteme(e);
		if (!e.isActif()) {
			throw new ErreurMetierException("DEJA_DESACTIVE", "Cet emplacement est déjà désactivé.");
		}
		if (e.getType() == TypeEmplacement.MAGASIN && emplacements.existsByMagasinRattachementIdAndActifTrue(id)) {
			throw new ErreurMetierException("MAGASIN_RATTACHE",
					"Des boutiques actives vendent sur le stock de ce magasin : changez d'abord leur rattachement.");
		}
		if (enUsage(id)) {
			throw new ErreurMetierException("EMPLACEMENT_EN_USAGE",
					"Cet emplacement a encore du stock ou des opérations en cours : il ne peut pas être désactivé.");
		}
		e.setActif(false);
		e.setModifiePar(acteur());
		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, "emplacement.desactive", "Emplacement", String.valueOf(id))
				.avecValeurs(Map.of("actif", true), Map.of("actif", false)));
		return EmplacementDto.de(e);
	}

	@Transactional
	public EmplacementDto reactiver(Long id) {
		Emplacement e = trouver(id);
		interdireSiSysteme(e);
		if (e.isActif()) {
			throw new ErreurMetierException("DEJA_ACTIF", "Cet emplacement est déjà actif.");
		}
		if (e.getMagasinRattachement() != null && !e.getMagasinRattachement().isActif()) {
			throw new ErreurMetierException("MAGASIN_INACTIF", "Le magasin de rattachement de cette boutique est désactivé.");
		}
		e.setActif(true);
		e.setModifiePar(acteur());
		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, "emplacement.reactive", "Emplacement", String.valueOf(id))
				.avecValeurs(Map.of("actif", false), Map.of("actif", true)));
		return EmplacementDto.de(e);
	}

	/** Règles communes à la création et à la modification : mode, rattachement et contacts. */
	private void appliquer(Emplacement e, EmplacementRequete requete) {
		if (e.getType() == TypeEmplacement.BOUTIQUE) {
			if (requete.modeBoutique() == null) {
				throw new ErreurValidationException("mode_boutique", "Le mode (A ou B) est requis pour une boutique.");
			}
			e.setModeBoutique(requete.modeBoutique());
			if (requete.modeBoutique() == ModeBoutique.B) {
				e.setMagasinRattachement(magasinDeRattachement(requete.magasinRattachementId()));
			} else {
				e.setMagasinRattachement(null);
			}
		} else {
			if (requete.modeBoutique() != null) {
				throw new ErreurValidationException("mode_boutique", "Seule une boutique a un mode.");
			}
			e.setModeBoutique(null);
			e.setMagasinRattachement(null);
		}

		e.setAdresse(vide(requete.adresse()));
		e.setResponsableNom(exiger(requete.responsableNom(), "responsable_nom", "Le responsable est requis."));
		e.setEmail(exiger(requete.email(), "email", "L'adresse e-mail est requise."));
		e.setTelephone(exiger(requete.telephone(), "telephone", "Le téléphone est requis."));
	}

	private Emplacement magasinDeRattachement(Long id) {
		if (id == null) {
			throw new ErreurValidationException("magasin_rattachement_id", "Une boutique en mode B doit être rattachée à un magasin.");
		}
		Emplacement magasin = emplacements.findById(id)
				.orElseThrow(() -> new ErreurValidationException("magasin_rattachement_id", "Magasin de rattachement introuvable."));
		if (magasin.getType() != TypeEmplacement.MAGASIN) {
			throw new ErreurValidationException("magasin_rattachement_id", "Le rattachement doit être un magasin.");
		}
		if (!magasin.isActif()) {
			throw new ErreurValidationException("magasin_rattachement_id", "Ce magasin est désactivé.");
		}
		return magasin;
	}

	private boolean enUsage(Long id) {
		return usages.stream().anyMatch(u -> u.aStockOuOperationEnCours(id));
	}

	private Emplacement trouver(Long id) {
		return emplacements.findById(id)
				.orElseThrow(() -> new ErreurMetierException("EMPLACEMENT_INTROUVABLE", "Emplacement introuvable.", HttpStatus.NOT_FOUND));
	}

	private static void interdireSiSysteme(Emplacement e) {
		if (e.isSysteme()) {
			throw new ErreurMetierException("EMPLACEMENT_SYSTEME", "Cet emplacement est géré par le système.");
		}
	}

	private static Long idRattachement(Emplacement e) {
		return e.getMagasinRattachement() == null ? null : e.getMagasinRattachement().getId();
	}

	private Utilisateur acteur() {
		return ActeurAuthentifie.courant().flatMap(a -> utilisateurs.findById(a.id())).orElse(null);
	}

	private static String exiger(String valeur, String champ, String message) {
		String v = vide(valeur);
		if (v == null) {
			throw new ErreurValidationException(champ, message);
		}
		return v;
	}

	private static String vide(String valeur) {
		return valeur == null || valeur.isBlank() ? null : valeur.trim();
	}

	private static Map<String, Object> resume(Emplacement e) {
		Map<String, Object> valeurs = new LinkedHashMap<>();
		valeurs.put("code", e.getCode());
		valeurs.put("nom", e.getNom());
		valeurs.put("type", e.getType());
		valeurs.put("mode_boutique", e.getModeBoutique());
		valeurs.put("magasin_rattachement", e.getMagasinRattachement() == null ? null : e.getMagasinRattachement().getCode());
		valeurs.put("adresse", e.getAdresse());
		valeurs.put("responsable_nom", e.getResponsableNom());
		valeurs.put("email", e.getEmail());
		valeurs.put("telephone", e.getTelephone());
		valeurs.put("actif", e.isActif());
		return valeurs;
	}

}
