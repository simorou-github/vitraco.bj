package bj.vitraco.api.stk;

import bj.vitraco.api.aud.EntreeAudit;
import bj.vitraco.api.aud.JournalAudit;
import bj.vitraco.api.cat.Modele;
import bj.vitraco.api.cat.ModeleRepository;
import bj.vitraco.api.cat.chassis.ChassisService;
import bj.vitraco.api.cat.chassis.CreationChassis;
import bj.vitraco.api.cat.chassis.NumeroChassis;
import bj.vitraco.api.cat.chassis.StatutChassis;
import bj.vitraco.api.common.CodeModule;
import bj.vitraco.api.common.ErreurValidationException;
import bj.vitraco.api.common.FichierTabulaire;
import bj.vitraco.api.common.security.ActeurAuthentifie;
import bj.vitraco.api.stk.ImportStockDto.Rapport;
import bj.vitraco.api.stk.ImportStockDto.Rejet;
import bj.vitraco.api.stk.ImportStockDto.Repartition;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Import du stock initial par châssis depuis Excel ou CSV (US-006, SRS 14.2) : chaque moto déjà présente dans un magasin
 * ou une boutique entre au stock avec son modèle, son emplacement, sa date d'entrée et, si on le connaît, son coût. Une
 * ligne fautive est rejetée avec son numéro de ligne et son motif ; aucun châssis en double n'est importé ; un rapport
 * est conservé. La simulation produit le même rapport sans rien créer.
 */
@Service
public class ImportStockService {

	private static final int LIGNES_MAX = 10_000;
	private static final DateTimeFormatter[] FORMATS_DATE = {DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT),
			DateTimeFormatter.ofPattern("d-M-uuuu").withResolverStyle(ResolverStyle.STRICT)};

	private final JdbcClient jdbc;
	private final JsonMapper json;
	private final ModeleRepository modeles;
	private final ChassisService chassisService;
	private final JournalAudit journal;
	private final Clock horloge;

	ImportStockService(JdbcClient jdbc, JsonMapper json, ModeleRepository modeles, ChassisService chassisService, JournalAudit journal, Clock horloge) {
		this.jdbc = jdbc;
		this.json = json;
		this.modeles = modeles;
		this.chassisService = chassisService;
		this.journal = journal;
		this.horloge = horloge;
	}

	private record Lieu(Long id, String code, String nom) {
	}

	@Transactional
	public Rapport importer(String nomFichier, byte[] contenu, boolean simulation) {
		List<String[]> lignes = FichierTabulaire.lire(nomFichier == null ? "" : nomFichier, contenu);
		if (lignes.isEmpty()) {
			throw new ErreurValidationException("fichier", "Le fichier est vide.");
		}
		if (lignes.size() - 1 > LIGNES_MAX) {
			throw new ErreurValidationException("fichier", "Le fichier dépasse " + LIGNES_MAX + " lignes : découpez-le.");
		}
		Map<String, Integer> col = colonnes(lignes.get(0));

		Map<String, Lieu> lieux = new LinkedHashMap<>();
		jdbc.sql("SELECT id, code, nom FROM emplacements WHERE actif AND type IN ('MAGASIN', 'BOUTIQUE')")
				.query((rs, n) -> new Lieu(rs.getLong("id"), rs.getString("code"), rs.getString("nom"))).list()
				.forEach(l -> {
					lieux.put(FichierTabulaire.normaliser(l.code()), l);
					lieux.putIfAbsent(FichierTabulaire.normaliser(l.nom()), l);
				});

		Long importId = simulation ? null : jdbc.sql("""
						INSERT INTO imports_stock (nom_fichier, nb_lignes, nb_importees, nb_rejetees, cree_par) VALUES (:f, 0, 0, 0, :u) RETURNING id""")
				.param("f", nomFichier == null || nomFichier.isBlank() ? "stock-initial" : nomFichier)
				.param("u", ActeurAuthentifie.courant().map(ActeurAuthentifie::id).orElse(null)).query(Long.class).single();

		List<Rejet> rejets = new ArrayList<>();
		Set<String> vus = new HashSet<>();
		Map<Long, int[]> nombres = new LinkedHashMap<>();
		Map<Long, long[]> couts = new LinkedHashMap<>();
		Map<Long, Lieu> lieuxVus = new LinkedHashMap<>();
		int lues = 0;
		int importees = 0;
		long valeur = 0;
		for (int i = 1; i < lignes.size(); i++) {
			String[] c = lignes.get(i);
			int numeroLigne = i + 1;
			String brut = FichierTabulaire.cellule(c, col.get("chassis"));
			String code = FichierTabulaire.cellule(c, col.get("article"));
			String lieuTexte = FichierTabulaire.cellule(c, col.get("emplacement"));
			if (brut.isEmpty() && code.isEmpty() && lieuTexte.isEmpty()) {
				continue; // ligne vide : ni importée ni comptée
			}
			lues++;
			String numero = NumeroChassis.normaliser(brut);
			String motif = null;
			Modele modele = null;
			Lieu lieu = null;
			StatutChassis statut = StatutChassis.DISPONIBLE;
			LocalDate dateEntree = null;
			Long cout = null;
			String motifBlocage = FichierTabulaire.cellule(c, col.get("motif"));
			if (brut.isEmpty()) {
				motif = "numéro de châssis manquant";
			} else if (code.isEmpty()) {
				motif = "code article manquant";
			} else if ((modele = modeles.findByCodeArticle(code).orElse(null)) == null) {
				motif = "code article inconnu : " + code;
			} else if (lieuTexte.isEmpty()) {
				motif = "emplacement manquant";
			} else if ((lieu = lieux.get(FichierTabulaire.normaliser(lieuTexte))) == null) {
				motif = "emplacement inconnu, désactivé ou qui n'est ni un magasin ni une boutique : " + lieuTexte;
			} else if (vus.contains(numero)) {
				motif = "doublon dans le fichier";
			} else {
				String texteStatut = FichierTabulaire.normaliser(FichierTabulaire.cellule(c, col.get("statut")));
				if (!texteStatut.isEmpty() && !Set.of("disponible", "dispo").contains(texteStatut)) {
					if (Set.of("bloquee", "bloque", "bloqué").contains(texteStatut)) {
						statut = StatutChassis.BLOQUEE;
						if (motifBlocage.isEmpty()) {
							motif = "un châssis bloqué exige un motif";
						}
					} else {
						motif = "statut non repris : " + FichierTabulaire.cellule(c, col.get("statut")) + " (seuls « disponible » et « bloqué » s'importent)";
					}
				}
				if (motif == null) {
					String texteDate = FichierTabulaire.cellule(c, col.get("date"));
					try {
						dateEntree = texteDate.isEmpty() ? null : lireDate(texteDate);
						if (dateEntree != null && dateEntree.isAfter(LocalDate.now(horloge))) {
							motif = "date d'entrée dans le futur : " + texteDate;
						}
					} catch (DateTimeParseException e) {
						motif = "date d'entrée illisible : " + texteDate + " (jj/mm/aaaa ou aaaa-mm-jj)";
					}
				}
				if (motif == null) {
					try {
						BigDecimal valeurCout = ImportStockDto.coutOuNull(FichierTabulaire.cellule(c, col.get("cout")));
						if (valeurCout != null) {
							if (valeurCout.signum() < 0) {
								motif = "coût de revient négatif";
							} else {
								cout = valeurCout.setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
							}
						}
					} catch (NumberFormatException | ArithmeticException e) {
						motif = "coût de revient illisible : " + FichierTabulaire.cellule(c, col.get("cout"));
					}
				}
				if (motif == null) {
					// Doublon en base et format du modèle : même contrôle que pour toute création de châssis.
					motif = chassisService.controler(brut, modele.getId()).orElse(null);
				}
			}
			if (motif != null) {
				rejets.add(new Rejet(numeroLigne, brut.isEmpty() ? null : numero, motif));
				continue;
			}
			vus.add(numero); // seule une ligne acceptée occupe le numéro : une ligne corrigée plus bas reste importable
			importees++;
			if (cout != null) {
				valeur += cout;
			}
			lieuxVus.putIfAbsent(lieu.id(), lieu);
			nombres.computeIfAbsent(lieu.id(), k -> new int[1])[0]++;
			couts.computeIfAbsent(lieu.id(), k -> new long[1])[0] += cout == null ? 0 : cout;
			if (!simulation) {
				chassisService.creer(new CreationChassis(brut, modele.getId(), statut, lieu.id(), null, null, cout, vide(FichierTabulaire.cellule(c, col.get("moteur"))),
						vide(FichierTabulaire.cellule(c, col.get("couleur"))), "IMPORT_STOCK", importId, statut == StatutChassis.BLOQUEE ? motifBlocage : "Stock initial"), dateEntree);
			}
		}

		List<Repartition> repartition = lieuxVus.values().stream()
				.map(l -> new Repartition(l.id(), l.code(), l.nom(), nombres.get(l.id())[0], couts.get(l.id())[0])).toList();
		if (!simulation) {
			jdbc.sql("""
							UPDATE imports_stock SET nb_lignes = :l, nb_importees = :i, nb_rejetees = :r, repartition = CAST(:rep AS jsonb), rejets = CAST(:rej AS jsonb) WHERE id = :id""")
					.param("l", lues).param("i", importees).param("r", rejets.size()).param("rep", json.writeValueAsString(repartition))
					.param("rej", json.writeValueAsString(rejets)).param("id", importId).update();
			journal.enregistrer(EntreeAudit.de(CodeModule.STK, "stock.import_initial", "ImportStock", String.valueOf(importId))
					.avecValeurs(null, Map.of("fichier", nomFichier == null ? "" : nomFichier, "lignes", lues, "importees", importees, "rejetees", rejets.size())));
		}
		return new Rapport(importId, nomFichier, lues, importees, rejets.size(), simulation, repartition, rejets, valeur);
	}

	@Transactional(readOnly = true)
	public List<ImportStockDto.Historique> historique() {
		return jdbc.sql("""
						SELECT i.id, i.nom_fichier, i.nb_lignes, i.nb_importees, i.nb_rejetees, i.cree_le, u.nom || coalesce(' ' || u.prenom, '') AS auteur
						FROM imports_stock i LEFT JOIN utilisateurs u ON u.id = i.cree_par ORDER BY i.cree_le DESC, i.id DESC LIMIT 50""")
				.query((rs, n) -> new ImportStockDto.Historique(rs.getLong("id"), rs.getString("nom_fichier"), rs.getInt("nb_lignes"), rs.getInt("nb_importees"),
						rs.getInt("nb_rejetees"), rs.getString("auteur"), rs.getTimestamp("cree_le").toInstant()))
				.list();
	}

	@Transactional(readOnly = true)
	public Rapport rapport(Long id) {
		return jdbc.sql("SELECT nom_fichier, nb_lignes, nb_importees, nb_rejetees, repartition, rejets FROM imports_stock WHERE id = :i").param("i", id)
				.query((rs, n) -> new Rapport(id, rs.getString("nom_fichier"), rs.getInt("nb_lignes"), rs.getInt("nb_importees"), rs.getInt("nb_rejetees"), false,
						lire(rs.getString("repartition"), Repartition.class), lire(rs.getString("rejets"), Rejet.class),
						lire(rs.getString("repartition"), Repartition.class).stream().mapToLong(Repartition::coutFcfa).sum()))
				.optional().orElseThrow(() -> new bj.vitraco.api.common.ErreurMetierException("IMPORT_INTROUVABLE", "Import introuvable.", org.springframework.http.HttpStatus.NOT_FOUND));
	}

	private <T> List<T> lire(String jsonTexte, Class<T> type) {
		return json.readValue(jsonTexte, json.getTypeFactory().constructCollectionType(List.class, type));
	}

	/** Le modèle de fichier à remplir : titres, une ligne d'exemple et les consignes. */
	public byte[] modele() {
		try (Workbook classeur = new XSSFWorkbook(); ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
			Sheet feuille = classeur.createSheet("Stock initial");
			String[] titres = {"numero_chassis", "code_article", "emplacement", "statut", "date_entree", "cout_revient", "numero_moteur", "couleur", "motif_blocage"};
			Row entete = feuille.createRow(0);
			for (int i = 0; i < titres.length; i++) {
				entete.createCell(i).setCellValue(titres[i]);
				feuille.setColumnWidth(i, 5200);
			}
			String[] exemple = {"LWBAB12CD34EF56GH", "CG125-2026", "MAG-COT", "disponible", "15/09/2026", "980000", "MOT123456", "Rouge", ""};
			Row ligne = feuille.createRow(1);
			for (int i = 0; i < exemple.length; i++) {
				ligne.createCell(i).setCellValue(exemple[i]);
			}
			Sheet notes = classeur.createSheet("Consignes");
			String[] consignes = {
					"Une ligne par moto présente aujourd'hui dans un magasin ou une boutique.",
					"numero_chassis, code_article et emplacement sont obligatoires. Le code article doit exister au catalogue ; l'emplacement est son code ou son nom.",
					"statut : « disponible » (par défaut) ou « bloqué » (le motif est alors obligatoire). Les motos réservées, livrées ou en dépôt-vente ne s'importent pas ici.",
					"date_entree : jj/mm/aaaa ou aaaa-mm-jj ; vide = date du jour. cout_revient en FCFA, facultatif.",
					"Une ligne fautive est rejetée avec son motif, les bonnes passent. Aucun châssis déjà connu n'est importé deux fois.",
					"Faites d'abord une simulation : elle produit le rapport sans rien créer."};
			for (int i = 0; i < consignes.length; i++) {
				notes.createRow(i).createCell(0).setCellValue(consignes[i]);
			}
			notes.setColumnWidth(0, 22000);
			classeur.write(sortie);
			return sortie.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException("Génération du modèle impossible", e);
		}
	}

	private static LocalDate lireDate(String texte) {
		DateTimeParseException derniere = null;
		for (DateTimeFormatter format : FORMATS_DATE) {
			try {
				return LocalDate.parse(texte, format);
			} catch (DateTimeParseException e) {
				derniere = e;
			}
		}
		throw derniere;
	}

	private static Map<String, Integer> colonnes(String[] entete) {
		Map<String, Integer> trouvees = new LinkedHashMap<>();
		for (int i = 0; i < entete.length; i++) {
			String cle = FichierTabulaire.normaliser(entete[i]);
			if (Set.of("numerochassis", "chassis", "numero", "vin", "nchassis", "numerodechassis").contains(cle)) {
				trouvees.putIfAbsent("chassis", i);
			} else if (Set.of("codearticle", "article", "modele", "code", "reference", "codemodele").contains(cle)) {
				trouvees.putIfAbsent("article", i);
			} else if (Set.of("emplacement", "site", "lieu", "magasin", "codeemplacement").contains(cle)) {
				trouvees.putIfAbsent("emplacement", i);
			} else if (Set.of("statut", "etat").contains(cle)) {
				trouvees.putIfAbsent("statut", i);
			} else if (Set.of("dateentree", "dateentreeenstock", "date", "entree").contains(cle)) {
				trouvees.putIfAbsent("date", i);
			} else if (Set.of("coutrevient", "cout", "coutderevient").contains(cle)) {
				trouvees.putIfAbsent("cout", i);
			} else if (Set.of("numeromoteur", "moteur", "nmoteur", "numerodemoteur").contains(cle)) {
				trouvees.putIfAbsent("moteur", i);
			} else if (Set.of("couleur", "color", "coloris").contains(cle)) {
				trouvees.putIfAbsent("couleur", i);
			} else if (Set.of("motifblocage", "motif").contains(cle)) {
				trouvees.putIfAbsent("motif", i);
			}
		}
		if (!trouvees.containsKey("chassis") || !trouvees.containsKey("article") || !trouvees.containsKey("emplacement")) {
			throw new ErreurValidationException("fichier", "Colonnes attendues sur la première ligne : « numero_chassis », « code_article » et « emplacement » "
					+ "(facultatives : « statut », « date_entree », « cout_revient », « numero_moteur », « couleur », « motif_blocage »).");
		}
		return trouvees;
	}

	private static String vide(String valeur) {
		return valeur == null || valeur.isBlank() ? null : valeur.trim();
	}

}
