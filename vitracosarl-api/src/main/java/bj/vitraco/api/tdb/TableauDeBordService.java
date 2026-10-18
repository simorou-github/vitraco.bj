package bj.vitraco.api.tdb;

import bj.vitraco.api.adm.parametres.CatalogueParametres;
import bj.vitraco.api.adm.parametres.GroupeParametre;
import bj.vitraco.api.adm.parametres.ParametreDto;
import bj.vitraco.api.adm.parametres.ParametreService;
import bj.vitraco.api.tdb.SyntheseAdministration.Activite;
import bj.vitraco.api.tdb.SyntheseAdministration.Alerte;
import bj.vitraco.api.tdb.SyntheseAdministration.Alerte.Niveau;
import bj.vitraco.api.tdb.SyntheseAdministration.Avancement;
import bj.vitraco.api.tdb.SyntheseAdministration.EtapeMiseEnService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
class TableauDeBordService {

	private static final int ALERTE_JETON_JOURS = 30;
	private static final int TAILLE_ACTIVITE = 8;

	private final JdbcClient jdbc;
	private final ParametreService parametres;
	private final Clock horloge;

	TableauDeBordService(JdbcClient jdbc, ParametreService parametres, Clock horloge) {
		this.jdbc = jdbc;
		this.parametres = parametres;
		this.horloge = horloge;
	}

	@Transactional(readOnly = true)
	SyntheseAdministration administration(boolean avecActivite) {
		var utilisateurs = jdbc.sql("""
						SELECT count(*) AS total,
						       count(*) FILTER (WHERE statut = 'ACTIF') AS actifs,
						       count(*) FILTER (WHERE statut = 'EN_ATTENTE_ACTIVATION') AS en_attente,
						       count(*) FILTER (WHERE statut = 'SUSPENDU') AS suspendus,
						       count(*) FILTER (WHERE verrouille_jusqua IS NOT NULL AND verrouille_jusqua > now()) AS verrouilles
						FROM utilisateurs""")
				.query((rs, n) -> new SyntheseAdministration.Utilisateurs(rs.getLong("total"), rs.getLong("actifs"),
						rs.getLong("en_attente"), rs.getLong("suspendus"), rs.getLong("verrouilles")))
				.single();

		var emplacements = jdbc.sql("""
						SELECT count(*) FILTER (WHERE type = 'MAGASIN' AND actif) AS magasins,
						       count(*) FILTER (WHERE type = 'BOUTIQUE' AND actif) AS boutiques,
						       count(*) FILTER (WHERE NOT actif) AS desactives
						FROM emplacements""")
				.query((rs, n) -> new SyntheseAdministration.Emplacements(rs.getLong("magasins"), rs.getLong("boutiques"), rs.getLong("desactives")))
				.single();

		var referentiels = new SyntheseAdministration.Referentiels(
				actifs("fournisseurs"), actifs("banques"), actifs("modes_paiement"), actifs("categories_depense"), actifs("centres_cout"));

		List<ParametreDto> liste = parametres.lister();
		var parametresAvancement = new SyntheseAdministration.Parametres(
				avancement(liste, GroupeParametre.FISCAL), avancement(liste, GroupeParametre.CONTROLE));

		long roles = jdbc.sql("SELECT count(*) FROM roles").query(Long.class).single();

		return new SyntheseAdministration(utilisateurs, emplacements, referentiels, parametresAvancement, roles,
				etapes(emplacements, utilisateurs, referentiels, liste), alertes(utilisateurs, liste),
				avecActivite ? activite() : null);
	}

	private long actifs(String table) {
		// Les noms de table viennent de ce fichier, jamais d'une saisie.
		return jdbc.sql("SELECT count(*) FROM " + table + " WHERE actif").query(Long.class).single();
	}

	private static Avancement avancement(List<ParametreDto> liste, GroupeParametre groupe) {
		// Les politiques à valeur par défaut (séparation des tâches) ne sont pas des réglages à faire avant la mise en service.
		var duGroupe = liste.stream().filter(p -> p.groupe() == groupe && p.defaut() == null).toList();
		return new Avancement(duGroupe.stream().filter(ParametreDto::renseigne).count(), duGroupe.size());
	}

	private static List<EtapeMiseEnService> etapes(SyntheseAdministration.Emplacements e, SyntheseAdministration.Utilisateurs u,
												  SyntheseAdministration.Referentiels r, List<ParametreDto> parametres) {
		boolean ifu = parametres.stream().anyMatch(p -> p.cle().equals(CatalogueParametres.IFU) && p.renseigne());
		boolean controles = avancement(parametres, GroupeParametre.CONTROLE).renseignes() == avancement(parametres, GroupeParametre.CONTROLE).total();
		return List.of(
				new EtapeMiseEnService("magasin", "Créer au moins un magasin", e.magasins() > 0, "/emplacements"),
				new EtapeMiseEnService("boutique", "Créer au moins une boutique", e.boutiques() > 0, "/emplacements"),
				new EtapeMiseEnService("utilisateurs", "Inviter les premiers utilisateurs", u.total() > 1, "/utilisateurs/liste"),
				new EtapeMiseEnService("fournisseur", "Enregistrer un fournisseur", r.fournisseurs() > 0, "/referentiels-adm/fournisseurs"),
				new EtapeMiseEnService("banque", "Enregistrer une banque", r.banques() > 0, "/referentiels-adm/banques"),
				new EtapeMiseEnService("fiscal", "Renseigner l'IFU de VITRACO", ifu, "/parametres/fiscaux"),
				new EtapeMiseEnService("controle", "Fixer les seuils de contrôle", controles, "/parametres/controle"));
	}

	private List<Alerte> alertes(SyntheseAdministration.Utilisateurs u, List<ParametreDto> parametres) {
		List<Alerte> alertes = new ArrayList<>();
		if (u.verrouilles() > 0) {
			alertes.add(new Alerte(Niveau.ATTENTION, u.verrouilles() + (u.verrouilles() > 1 ? " comptes sont verrouillés" : " compte est verrouillé")
					+ " après des échecs de connexion.", "/utilisateurs/liste"));
		}
		Optional<LocalDate> expiration = parametres.stream()
				.filter(p -> p.cle().equals(CatalogueParametres.JETON_EMECEF_EXPIRATION) && p.renseigne())
				.map(p -> LocalDate.parse(p.valeur())).findFirst();
		expiration.ifPresent(date -> {
			long jours = ChronoUnit.DAYS.between(LocalDate.now(horloge), date);
			if (jours < 0) {
				alertes.add(new Alerte(Niveau.CRITIQUE, "Le jeton e-MECeF a expiré : plus aucune facture ne peut être normalisée.", "/parametres/fiscaux"));
			} else if (jours <= ALERTE_JETON_JOURS) {
				alertes.add(new Alerte(Niveau.ATTENTION, "Le jeton e-MECeF expire dans " + jours + (jours > 1 ? " jours." : " jour."), "/parametres/fiscaux"));
			}
		});
		return alertes;
	}

	private List<Activite> activite() {
		return jdbc.sql("""
						SELECT date_heure, utilisateur_libelle, action, module, objet_type, objet_id
						FROM journal_audit ORDER BY rang DESC LIMIT :n""")
				.param("n", TAILLE_ACTIVITE)
				.query((rs, n) -> new Activite(rs.getTimestamp("date_heure").toInstant(), rs.getString("utilisateur_libelle"),
						rs.getString("action"), rs.getString("module"), rs.getString("objet_type"), rs.getString("objet_id")))
				.list();
	}

}
