package bj.vitraco.api.app;

import bj.vitraco.api.aud.EntreeAudit;
import bj.vitraco.api.aud.JournalAudit;
import bj.vitraco.api.cat.Modele;
import bj.vitraco.api.cat.ModeleRepository;
import bj.vitraco.api.cat.chassis.ChassisService;
import bj.vitraco.api.cat.chassis.CreationChassis;
import bj.vitraco.api.cat.chassis.NumeroChassis;
import bj.vitraco.api.cat.chassis.StatutChassis;
import bj.vitraco.api.common.CodeModule;
import bj.vitraco.api.common.ErreurMetierException;
import bj.vitraco.api.common.ErreurValidationException;
import bj.vitraco.api.common.FichierTabulaire;
import bj.vitraco.api.common.security.ActeurAuthentifie;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Import de la liste de colisage d'un arrivage (EF-APP-06, US-023) : les châssis annoncés par le fournisseur sont créés
 * « en transit fournisseur », hors stock. Une ligne fautive (doublon, format, modèle hors commande, quantité dépassée) est
 * rejetée avec son numéro de ligne et son motif ; les bonnes lignes passent. Une simulation montre le rapport sans rien créer.
 */
@Service
public class ColisageService {

	public record RapportColisage(Long id, String nomFichier, int nbLignes, int nbImportees, int nbRejetees, boolean simulation,
								  List<ArrivageDto.Rejet> rejets) {
	}

	private static final int LIGNES_MAX = 5000;
	private static final Set<StatutCommande> IMPORT_POSSIBLE = EnumSet.of(StatutCommande.CONFIRMEE, StatutCommande.EN_TRANSIT, StatutCommande.AU_PORT,
			StatutCommande.DEDOUANEMENT);

	private final ArrivageRepository arrivages;
	private final ModeleRepository modeles;
	private final ChassisService chassis;
	private final JdbcClient jdbc;
	private final JsonMapper json;
	private final JournalAudit journal;

	ColisageService(ArrivageRepository arrivages, ModeleRepository modeles, ChassisService chassis, JdbcClient jdbc, JsonMapper json, JournalAudit journal) {
		this.arrivages = arrivages;
		this.modeles = modeles;
		this.chassis = chassis;
		this.jdbc = jdbc;
		this.json = json;
		this.journal = journal;
	}

	@Transactional
	public RapportColisage importer(Long arrivageId, String nomFichier, byte[] contenu, boolean simulation) {
		Arrivage arrivage = arrivages.findById(arrivageId)
				.orElseThrow(() -> new ErreurMetierException("ARRIVAGE_INTROUVABLE", "Arrivage introuvable.", HttpStatus.NOT_FOUND));
		CommandeFournisseur commande = arrivage.getCommande();
		if (arrivage.getStatutDouane() == StatutDouane.TERMINEE || !IMPORT_POSSIBLE.contains(commande.getStatut())) {
			throw new ErreurMetierException("IMPORT_IMPOSSIBLE",
					"Le colisage s'importe entre la confirmation de la commande et la fin du dédouanement de l'arrivage.");
		}
		List<String[]> lignes = FichierTabulaire.lire(nomFichier == null ? "" : nomFichier, contenu);
		if (lignes.isEmpty()) {
			throw new ErreurValidationException("fichier", "Le fichier est vide.");
		}
		Map<String, Integer> colonnes = colonnes(lignes.get(0));
		if (lignes.size() - 1 > LIGNES_MAX) {
			throw new ErreurValidationException("fichier", "Le fichier dépasse " + LIGNES_MAX + " lignes : découpez-le.");
		}

		// Quantités commandées par modèle, et déjà annoncées par les colisages des arrivages de la commande.
		Map<Long, Integer> commandees = new HashMap<>();
		commande.getLignes().forEach(l -> commandees.put(l.getModele().getId(), l.getQuantite()));
		Map<Long, Long> annonces = new HashMap<>();
		jdbc.sql("""
						SELECT c.modele_id, count(*) AS n FROM chassis c WHERE c.arrivage_id IN (SELECT id FROM arrivages WHERE commande_id = :c)
						GROUP BY c.modele_id""")
				.param("c", commande.getId()).query((rs, i) -> annonces.put(rs.getLong("modele_id"), rs.getLong("n"))).list();

		List<ArrivageDto.Rejet> rejets = new ArrayList<>();
		Set<String> vus = new HashSet<>();
		int importees = 0;
		int lues = 0;
		for (int i = 1; i < lignes.size(); i++) {
			String[] cellules = lignes.get(i);
			int numeroLigne = i + 1;
			String brut = FichierTabulaire.cellule(cellules, colonnes.get("chassis"));
			String code = FichierTabulaire.cellule(cellules, colonnes.get("article"));
			if (brut.isEmpty() && code.isEmpty()) {
				continue; // ligne vide : ni importée ni comptée
			}
			lues++;
			String numero = NumeroChassis.normaliser(brut);
			String motif = null;
			Modele modele = null;
			if (brut.isEmpty()) {
				motif = "numéro de châssis manquant";
			} else if (code.isEmpty()) {
				motif = "code article manquant";
			} else {
				modele = modeles.findByCodeArticle(code).orElse(null);
				if (modele == null) {
					motif = "code article inconnu : " + code;
				} else if (!commandees.containsKey(modele.getId())) {
					motif = "le modèle " + modele.getCodeArticle() + " ne figure pas sur la commande " + commande.getNumero();
				} else if (!vus.add(numero)) {
					motif = "doublon dans le fichier";
				} else {
					// Doublon et format d'abord : une moto déjà connue se signale comme telle, pas comme un dépassement.
					motif = chassis.controler(brut, modele.getId()).orElse(null);
					if (motif == null && annonces.getOrDefault(modele.getId(), 0L) >= commandees.get(modele.getId())) {
						motif = "quantité commandée dépassée pour " + modele.getCodeArticle() + " (" + commandees.get(modele.getId()) + ")";
					}
				}
			}
			if (motif != null) {
				rejets.add(new ArrivageDto.Rejet(numeroLigne, brut.isEmpty() ? null : numero, motif));
				continue;
			}
			annonces.merge(modele.getId(), 1L, Long::sum);
			importees++;
			if (!simulation) {
				chassis.creer(new CreationChassis(brut, modele.getId(), StatutChassis.EN_TRANSIT_FOURNISSEUR, null, arrivageId, null, null,
						vide(FichierTabulaire.cellule(cellules, colonnes.get("moteur"))), vide(FichierTabulaire.cellule(cellules, colonnes.get("couleur"))), "ARRIVAGE", arrivageId));
			}
		}

		Long id = null;
		if (!simulation) {
			id = jdbc.sql("""
							INSERT INTO imports_colisage (arrivage_id, nom_fichier, nb_lignes, nb_importees, nb_rejetees, rejets, cree_par)
							VALUES (:a, :f, :l, :i, :r, CAST(:rejets AS jsonb), :u) RETURNING id""")
					.param("a", arrivageId).param("f", nomFichier == null || nomFichier.isBlank() ? "colisage" : nomFichier).param("l", lues)
					.param("i", importees).param("r", rejets.size()).param("rejets", json.writeValueAsString(rejets))
					.param("u", ActeurAuthentifie.courant().map(ActeurAuthentifie::id).orElse(null)).query(Long.class).single();
			journal.enregistrer(EntreeAudit.de(CodeModule.APP, "arrivage.colisage_importe", "Arrivage", String.valueOf(arrivageId))
					.avecValeurs(null, Map.of("fichier", nomFichier == null ? "" : nomFichier, "lignes", lues, "importees", importees, "rejetees", rejets.size())));
		}
		return new RapportColisage(id, nomFichier, lues, importees, rejets.size(), simulation, rejets);
	}

	private static Map<String, Integer> colonnes(String[] entete) {
		Map<String, Integer> trouvees = new HashMap<>();
		for (int i = 0; i < entete.length; i++) {
			String cle = FichierTabulaire.normaliser(entete[i]);
			if (Set.of("numerochassis", "chassis", "numero", "vin", "nchassis", "numerodechassis").contains(cle)) {
				trouvees.putIfAbsent("chassis", i);
			} else if (Set.of("codearticle", "article", "modele", "code", "reference", "codemodele").contains(cle)) {
				trouvees.putIfAbsent("article", i);
			} else if (Set.of("numeromoteur", "moteur", "nmoteur", "numerodemoteur").contains(cle)) {
				trouvees.putIfAbsent("moteur", i);
			} else if (Set.of("couleur", "color", "coloris").contains(cle)) {
				trouvees.putIfAbsent("couleur", i);
			}
		}
		if (!trouvees.containsKey("chassis") || !trouvees.containsKey("article")) {
			throw new ErreurValidationException("fichier",
					"Colonnes attendues sur la première ligne : « numero_chassis » et « code_article » (facultatives : « numero_moteur », « couleur »).");
		}
		return trouvees;
	}

	private static String vide(String valeur) {
		return valeur == null || valeur.isBlank() ? null : valeur.trim();
	}

}
