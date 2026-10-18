package bj.vitraco.api.app;

import bj.vitraco.api.adm.numerotation.NumerotationService;
import bj.vitraco.api.adm.numerotation.TypeDocument;
import bj.vitraco.api.adm.referentiels.Fournisseur;
import bj.vitraco.api.adm.referentiels.FournisseurRepository;
import bj.vitraco.api.adm.utilisateurs.ActeurDto;
import bj.vitraco.api.adm.utilisateurs.Utilisateur;
import bj.vitraco.api.adm.utilisateurs.UtilisateurRepository;
import bj.vitraco.api.aud.EntreeAudit;
import bj.vitraco.api.aud.JournalAudit;
import bj.vitraco.api.cat.Modele;
import bj.vitraco.api.cat.ModeleRepository;
import bj.vitraco.api.common.CodeModule;
import bj.vitraco.api.common.ErreurMetierException;
import bj.vitraco.api.common.ErreurValidationException;
import bj.vitraco.api.common.security.ActeurAuthentifie;
import bj.vitraco.api.common.security.Droits;
import bj.vitraco.api.notif.DemandeNotification;
import bj.vitraco.api.notif.Notifications;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Commandes fournisseurs (EF-APP-01) et leur workflow (EF-APP-04) : chaque passage d'étape garde sa date et son auteur,
 * une étape ne se saute pas, un retour arrière exige un droit spécifique et un motif, et rien ne se supprime (RG-18).
 */
@Service
public class CommandeFournisseurService {

	public record Filtre(String recherche, StatutCommande statut, Long fournisseurId, LocalDate depuis, LocalDate jusqua) {
	}

	/** {@code cible} est facultative : si elle est donnée et n'est pas l'étape suivante, l'étape sautée est refusée (US-021). */
	public record Avancement(StatutCommande cible, String referenceProforma, String observation) {
	}

	private static final String PERMISSION_PRIX = "approvisionnements.creer";

	private final CommandeFournisseurRepository commandes;
	private final FournisseurRepository fournisseurs;
	private final ModeleRepository modeles;
	private final UtilisateurRepository utilisateurs;
	private final NumerotationService numerotation;
	private final List<ConditionEtapeCommande> conditions;
	private final JdbcClient jdbc;
	private final JournalAudit journal;
	private final Notifications notifications;
	private final Clock horloge;

	CommandeFournisseurService(CommandeFournisseurRepository commandes, FournisseurRepository fournisseurs, ModeleRepository modeles,
							   UtilisateurRepository utilisateurs, NumerotationService numerotation, List<ConditionEtapeCommande> conditions,
							   JdbcClient jdbc, JournalAudit journal, Notifications notifications, Clock horloge) {
		this.commandes = commandes;
		this.fournisseurs = fournisseurs;
		this.modeles = modeles;
		this.utilisateurs = utilisateurs;
		this.numerotation = numerotation;
		this.conditions = conditions;
		this.jdbc = jdbc;
		this.journal = journal;
		this.notifications = notifications;
		this.horloge = horloge;
	}

	@Transactional(readOnly = true)
	public Page<CommandeDto> lister(Filtre filtre, Pageable pagination) {
		Specification<CommandeFournisseur> spec = (racine, requete, cb) -> {
			List<Predicate> criteres = new ArrayList<>();
			if (filtre.recherche() != null && !filtre.recherche().isBlank()) {
				String motif = "%" + filtre.recherche().trim().toLowerCase(Locale.ROOT) + "%";
				criteres.add(cb.or(cb.like(cb.lower(racine.get("numero")), motif),
						cb.like(cb.lower(cb.coalesce(racine.get("referenceProforma"), "")), motif),
						cb.like(cb.lower(racine.join("fournisseur").get("raisonSociale")), motif)));
			}
			if (filtre.statut() != null) {
				criteres.add(cb.equal(racine.get("statut"), filtre.statut()));
			}
			if (filtre.fournisseurId() != null) {
				criteres.add(cb.equal(racine.get("fournisseur").get("id"), filtre.fournisseurId()));
			}
			if (filtre.depuis() != null) {
				criteres.add(cb.greaterThanOrEqualTo(racine.get("dateCommande"), filtre.depuis()));
			}
			if (filtre.jusqua() != null) {
				criteres.add(cb.lessThanOrEqualTo(racine.get("dateCommande"), filtre.jusqua()));
			}
			return cb.and(criteres.toArray(new Predicate[0]));
		};
		return commandes.findAll(spec, pagination).map(c -> versDto(c, false));
	}

	@Transactional(readOnly = true)
	public CommandeDto detail(Long id) {
		return versDto(trouver(id), true);
	}

	@Transactional
	public CommandeDto creer(CommandeRequete requete) {
		Fournisseur fournisseur = fournisseurActif(requete.fournisseurId());
		CommandeFournisseur c = new CommandeFournisseur(numerotation.prochainNumero(TypeDocument.COMMANDE_FOURNISSEUR, null), fournisseur);
		appliquer(c, requete, fournisseur);
		c.setCreePar(acteur());
		commandes.saveAndFlush(c);

		historique(c.getId(), "CREATION", null, StatutCommande.COMMANDEE, null);
		journal.enregistrer(EntreeAudit.de(CodeModule.APP, "commande_fournisseur.creee", "CommandeFournisseur", String.valueOf(c.getId()))
				.avecValeurs(null, resume(c)));
		notifications.notifierRole("direction", DemandeNotification.de("commande_fournisseur.creee", "Nouvelle commande fournisseur : " + c.getNumero(),
				auteur() + " a créé la commande " + c.getNumero() + " auprès de " + fournisseur.getRaisonSociale() + " ("
						+ totalQuantite(c) + " moto(s)).", "/approvisionnements/commandes/" + c.getId()));
		return versDto(c, true);
	}

	@Transactional
	public CommandeDto modifier(Long id, CommandeRequete requete) {
		CommandeFournisseur c = trouver(id);
		if (!c.getStatut().modifiable()) {
			throw new ErreurMetierException("COMMANDE_NON_MODIFIABLE",
					"Une commande ne se modifie plus une fois confirmée par le fournisseur : annulez-la et créez-en une nouvelle.");
		}
		Map<String, Object> avant = resume(c);
		appliquer(c, requete, fournisseurActif(requete.fournisseurId()));
		c.setModifiePar(acteur());
		commandes.saveAndFlush(c);
		journal.enregistrer(EntreeAudit.de(CodeModule.APP, "commande_fournisseur.modifiee", "CommandeFournisseur", String.valueOf(id))
				.avecValeurs(avant, resume(c)));
		return versDto(c, true);
	}

	@Transactional
	public CommandeDto avancer(Long id, Avancement demande) {
		CommandeFournisseur c = trouver(id);
		StatutCommande avant = c.getStatut();
		if (avant == StatutCommande.ANNULEE) {
			throw new ErreurMetierException("COMMANDE_ANNULEE", "Cette commande est annulée.");
		}
		StatutCommande cible = avant.suivant().orElseThrow(() -> new ErreurMetierException("ETAPE_AUTOMATIQUE",
				"Les statuts de réception se mettent à jour seuls, à la validation des réceptions."));
		if (demande.cible() != null && demande.cible() != cible) {
			throw new ErreurMetierException("ETAPE_SAUTEE", "Une étape ne se saute pas : après « " + avant.libelle() + " » vient « " + cible.libelle() + " ».");
		}
		if (avant == StatutCommande.COMMANDEE) {
			String proforma = vide(demande.referenceProforma());
			if (proforma == null) {
				throw new ErreurValidationException("reference_proforma", "La référence de la proforma du fournisseur est requise pour confirmer la commande.");
			}
			c.setReferenceProforma(proforma);
		}
		exigerConditions(c, cible);
		if (vide(demande.observation()) != null) {
			c.setObservation(vide(demande.observation()));
		}
		changerStatut(c, cible, "AVANCEMENT", vide(demande.observation()));
		if (cible == StatutCommande.A_RECEPTIONNER) {
			notifications.notifierRole("magasin", DemandeNotification.de("commande_fournisseur.a_receptionner", "Arrivage à réceptionner : " + c.getNumero(),
					"La commande " + c.getNumero() + " (" + c.getFournisseur().getRaisonSociale() + ", " + totalQuantite(c)
							+ " moto(s)) est sortie du port et attend sa réception.", "/approvisionnements/commandes/" + c.getId()));
		}
		return versDto(c, true);
	}

	@Transactional
	public CommandeDto reculer(Long id, String motif) {
		CommandeFournisseur c = trouver(id);
		StatutCommande cible = c.getStatut().precedent().orElseThrow(() -> new ErreurMetierException("RETOUR_IMPOSSIBLE",
				"On ne revient pas en arrière depuis « " + c.getStatut().libelle() + " »."));
		if (vide(motif) == null) {
			throw new ErreurValidationException("motif", "Le motif du retour arrière est obligatoire.");
		}
		exigerConditions(c, cible);
		changerStatut(c, cible, "RETOUR_ARRIERE", motif.trim());
		return versDto(c, true);
	}

	@Transactional
	public CommandeDto annuler(Long id, String motif) {
		CommandeFournisseur c = trouver(id);
		if (!c.getStatut().annulable()) {
			throw new ErreurMetierException("ANNULATION_IMPOSSIBLE",
					"Une commande ne s'annule qu'avant le départ de la marchandise (statut « Commandée » ou « Confirmée »).");
		}
		if (vide(motif) == null) {
			throw new ErreurValidationException("motif", "Le motif de l'annulation est obligatoire.");
		}
		changerStatut(c, StatutCommande.ANNULEE, "ANNULATION", motif.trim());
		return versDto(c, true);
	}

	/**
	 * Appelé par la validation d'une réception (module REC) : cumule la quantité reçue d'un modèle et fait passer la
	 * commande à « Réception partielle » puis « Réceptionnée ». Ne touche jamais au stock (RG-06).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public StatutCommande enregistrerReception(Long commandeId, Long modeleId, int quantite) {
		CommandeFournisseur c = trouver(commandeId);
		if (!c.getStatut().recoitDesReceptions()) {
			throw new ErreurMetierException("COMMANDE_NON_RECEPTIONNABLE", "Cette commande ne peut pas recevoir de réception (statut « " + c.getStatut().libelle() + " »).");
		}
		if (quantite <= 0) {
			throw new ErreurValidationException("quantite", "La quantité reçue doit être positive.");
		}
		LigneCommande ligne = c.getLignes().stream().filter(l -> l.getModele().getId().equals(modeleId)).findFirst()
				.orElseThrow(() -> new ErreurMetierException("MODELE_HORS_COMMANDE", "Ce modèle ne figure pas sur la commande."));
		ligne.ajouterRecue(quantite);
		StatutCommande nouveau = c.getLignes().stream().allMatch(l -> l.getQuantiteRecue() >= l.getQuantite())
				? StatutCommande.RECEPTIONNEE : StatutCommande.RECEPTION_PARTIELLE;
		commandes.saveAndFlush(c);
		if (nouveau != c.getStatut()) {
			changerStatut(c, nouveau, "RECEPTION", "Réception de " + quantite + " × " + ligne.getModele().getCodeArticle());
		}
		return c.getStatut();
	}

	private void changerStatut(CommandeFournisseur c, StatutCommande cible, String type, String motif) {
		StatutCommande avant = c.getStatut();
		c.setStatut(cible);
		c.setModifiePar(acteur());
		commandes.saveAndFlush(c);
		historique(c.getId(), type, avant, cible, motif);
		String action = switch (type) {
			case "RETOUR_ARRIERE" -> "commande_fournisseur.retour_arriere";
			case "ANNULATION" -> "commande_fournisseur.annulee";
			case "RECEPTION" -> "commande_fournisseur.receptionnee";
			default -> "commande_fournisseur.avancee";
		};
		journal.enregistrer(EntreeAudit.de(CodeModule.APP, action, "CommandeFournisseur", String.valueOf(c.getId()))
				.avecValeurs(Map.of("statut", avant), Map.of("statut", cible)).avecMotif(motif));
	}

	private void exigerConditions(CommandeFournisseur c, StatutCommande cible) {
		for (ConditionEtapeCommande condition : conditions) {
			condition.refus(c.getId(), cible).ifPresent(motif -> {
				throw new ErreurMetierException("ETAPE_REFUSEE", "Passage à « " + cible.libelle() + " » impossible : " + motif);
			});
		}
	}

	/** Règles communes à la création et à la modification : fournisseur, devise et taux, modèles actifs et uniques. */
	private void appliquer(CommandeFournisseur c, CommandeRequete requete, Fournisseur fournisseur) {
		String devise = requete.devise() == null || requete.devise().isBlank() ? fournisseur.getDevise() : requete.devise().trim().toUpperCase(Locale.ROOT);
		BigDecimal taux = requete.tauxChange();
		if (devise.equals("XOF")) {
			if (taux != null && taux.compareTo(BigDecimal.ONE) != 0) {
				throw new ErreurValidationException("taux_change", "Une commande en francs CFA (XOF) a un taux de change de 1.");
			}
			taux = BigDecimal.ONE;
		} else if (taux == null) {
			throw new ErreurValidationException("taux_change", "Le taux de change vers le franc CFA est requis pour une commande en " + devise + ".");
		}

		Set<Long> vus = new HashSet<>();
		Map<Long, CommandeRequete.LigneRequete> demandees = new LinkedHashMap<>();
		Map<Long, Modele> modelesDemandes = new LinkedHashMap<>();
		for (CommandeRequete.LigneRequete l : requete.lignes()) {
			if (!vus.add(l.modeleId())) {
				throw new ErreurValidationException("lignes", "Un modèle ne figure qu'une fois par commande : regroupez les quantités.");
			}
			Modele modele = modeles.findById(l.modeleId()).orElseThrow(() -> new ErreurValidationException("lignes", "Modèle inconnu."));
			if (!modele.isActif()) {
				throw new ErreurValidationException("lignes", "Le modèle " + modele.getCodeArticle() + " est désactivé : on ne le commande plus.");
			}
			demandees.put(l.modeleId(), l);
			modelesDemandes.put(l.modeleId(), modele);
		}
		// Fusion par modèle : une ligne conservée est mise à jour en place, sans quoi Hibernate insérerait la nouvelle
		// avant de supprimer l'ancienne et heurterait l'unicité (commande, modèle).
		c.getLignes().removeIf(existante -> !demandees.containsKey(existante.getModele().getId()));
		for (Map.Entry<Long, CommandeRequete.LigneRequete> e : demandees.entrySet()) {
			CommandeRequete.LigneRequete l = e.getValue();
			BigDecimal prix = l.prixUnitaire().setScale(2, RoundingMode.HALF_UP);
			c.getLignes().stream().filter(existante -> existante.getModele().getId().equals(e.getKey())).findFirst().ifPresentOrElse(
					existante -> existante.modifier(l.quantite(), prix),
					() -> c.getLignes().add(new LigneCommande(c, modelesDemandes.get(e.getKey()), l.quantite(), prix)));
		}

		c.setFournisseur(fournisseur);
		c.setDateCommande(requete.dateCommande() == null ? LocalDate.now(horloge) : requete.dateCommande());
		c.setDevise(devise);
		c.setTauxChange(taux);
		c.setIncoterm(requete.incoterm());
		c.setBasePrix(requete.basePrix());
		c.setObservation(vide(requete.observation()));
	}

	private Fournisseur fournisseurActif(Long id) {
		Fournisseur f = fournisseurs.findById(id).orElseThrow(() -> new ErreurValidationException("fournisseur_id", "Fournisseur inconnu."));
		if (!f.isActif()) {
			throw new ErreurValidationException("fournisseur_id", "Ce fournisseur est désactivé.");
		}
		return f;
	}

	private void historique(Long commandeId, String type, StatutCommande avant, StatutCommande apres, String motif) {
		var acteur = ActeurAuthentifie.courant();
		jdbc.sql("""
						INSERT INTO historique_commande_fournisseur (commande_id, type, statut_avant, statut_apres, motif, utilisateur_id, utilisateur_libelle)
						VALUES (:c, :t, :av, :ap, :m, :u, :ul)""")
				.param("c", commandeId).param("t", type).param("av", avant == null ? null : avant.name()).param("ap", apres.name())
				.param("m", motif).param("u", acteur.map(ActeurAuthentifie::id).orElse(null)).param("ul", acteur.map(ActeurAuthentifie::libelle).orElse(null))
				.update();
	}

	private CommandeFournisseur trouver(Long id) {
		return commandes.findById(id)
				.orElseThrow(() -> new ErreurMetierException("COMMANDE_INTROUVABLE", "Commande introuvable.", HttpStatus.NOT_FOUND));
	}

	private CommandeDto versDto(CommandeFournisseur c, boolean avecHistorique) {
		boolean prix = Droits.aPermission("prix_achat.voir") || Droits.aPermission(PERMISSION_PRIX);
		BigDecimal totalDevise = BigDecimal.ZERO;
		long totalFcfa = 0;
		List<CommandeDto.Ligne> lignes = new ArrayList<>();
		for (LigneCommande l : c.getLignes().stream().sorted(java.util.Comparator.comparing(l -> l.getModele().getCodeArticle())).toList()) {
			BigDecimal montant = l.getPrixUnitaire().multiply(BigDecimal.valueOf(l.getQuantite())).setScale(2, RoundingMode.HALF_UP);
			long fcfa = montant.multiply(c.getTauxChange()).setScale(0, RoundingMode.HALF_UP).longValueExact();
			totalDevise = totalDevise.add(montant);
			totalFcfa += fcfa;
			Modele m = l.getModele();
			lignes.add(new CommandeDto.Ligne(l.getId(), m.getId(), m.getMarque() + " " + m.getNom() + (m.getVersionModele() == null ? "" : " " + m.getVersionModele()),
					m.getCodeArticle(), l.getQuantite(), l.getQuantiteRecue(), l.getQuantite() - l.getQuantiteRecue(),
					prix ? l.getPrixUnitaire() : null, prix ? montant : null, prix ? fcfa : null));
		}
		var f = c.getFournisseur();
		Long frais = null;
		Long cout = null;
		Long moyen = null;
		Integer sansPiece = null;
		int nbArrivages = 0;
		if (avecHistorique) {
			nbArrivages = jdbc.sql("SELECT count(*) FROM arrivages WHERE commande_id = :c").param("c", c.getId()).query(Integer.class).single();
		}
		if (avecHistorique && prix) {
			var totaux = jdbc.sql("""
							SELECT coalesce(sum(f.montant_fcfa) FILTER (WHERE NOT f.annule), 0) AS total,
							       count(*) FILTER (WHERE NOT f.annule AND f.reference_piece IS NULL) AS sans_piece
							FROM frais_arrivage f JOIN arrivages a ON a.id = f.arrivage_id WHERE a.commande_id = :c""")
					.param("c", c.getId()).query((rs, n) -> new long[]{rs.getLong("total"), rs.getLong("sans_piece")}).single();
			frais = totaux[0];
			sansPiece = (int) totaux[1];
			cout = totalFcfa + frais;
			long motos = totalQuantite(c);
			moyen = motos == 0 ? null : BigDecimal.valueOf(cout).divide(BigDecimal.valueOf(motos), 0, RoundingMode.HALF_UP).longValueExact();
		}
		return new CommandeDto(c.getId(), c.getNumero(), new CommandeDto.Fournisseur(f.getId(), f.getRaisonSociale(), f.getPays()), c.getDateCommande(),
				c.getDevise(), prix ? c.getTauxChange() : null, c.getIncoterm(), c.getBasePrix(), c.getStatut(), c.getStatut().libelle(),
				c.getReferenceProforma(), c.getObservation(), lignes, totalQuantite(c), c.getLignes().stream().mapToLong(LigneCommande::getQuantiteRecue).sum(),
				prix, prix ? totalDevise : null, prix ? totalFcfa : null, frais, cout, moyen, sansPiece, nbArrivages, c.getStatut().suivant().orElse(null), c.getStatut().precedent().orElse(null),
				c.getStatut().annulable(), c.getStatut().modifiable(), avecHistorique ? etapes(c.getId()) : List.of(), c.getCreeLe(), ActeurDto.de(c.getCreePar()));
	}

	private List<CommandeDto.Etape> etapes(Long commandeId) {
		return jdbc.sql("""
						SELECT type, statut_avant, statut_apres, motif, utilisateur_libelle, cree_le
						FROM historique_commande_fournisseur WHERE commande_id = :c ORDER BY cree_le, id""")
				.param("c", commandeId)
				.query((rs, n) -> new CommandeDto.Etape(rs.getString("type"),
						rs.getString("statut_avant") == null ? null : StatutCommande.valueOf(rs.getString("statut_avant")),
						StatutCommande.valueOf(rs.getString("statut_apres")), rs.getString("motif"), rs.getString("utilisateur_libelle"),
						rs.getTimestamp("cree_le").toInstant()))
				.list();
	}

	private static long totalQuantite(CommandeFournisseur c) {
		return c.getLignes().stream().mapToLong(LigneCommande::getQuantite).sum();
	}

	private Utilisateur acteur() {
		return ActeurAuthentifie.courant().flatMap(a -> utilisateurs.findById(a.id())).orElse(null);
	}

	private String auteur() {
		return ActeurAuthentifie.courant().map(ActeurAuthentifie::libelle).orElse("Le système");
	}

	private static String vide(String valeur) {
		return valeur == null || valeur.isBlank() ? null : valeur.trim();
	}

	private static Map<String, Object> resume(CommandeFournisseur c) {
		Map<String, Object> valeurs = new LinkedHashMap<>();
		valeurs.put("numero", c.getNumero());
		valeurs.put("fournisseur", c.getFournisseur().getRaisonSociale());
		valeurs.put("devise", c.getDevise());
		valeurs.put("incoterm", c.getIncoterm());
		valeurs.put("base_prix", c.getBasePrix());
		valeurs.put("lignes", c.getLignes().stream().map(l -> l.getModele().getCodeArticle() + " x" + l.getQuantite()).sorted().toList());
		return valeurs;
	}

}
