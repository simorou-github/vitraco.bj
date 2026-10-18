package bj.vitraco.api.adm.parametres;

import bj.vitraco.api.adm.utilisateurs.ActeurDto;
import bj.vitraco.api.adm.utilisateurs.Utilisateur;
import bj.vitraco.api.adm.utilisateurs.UtilisateurRepository;
import bj.vitraco.api.aud.EntreeAudit;
import bj.vitraco.api.aud.JournalAudit;
import bj.vitraco.api.common.CodeModule;
import bj.vitraco.api.common.ErreurMetierException;
import bj.vitraco.api.common.ErreurValidationException;
import bj.vitraco.api.common.security.ActeurAuthentifie;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Valeurs des paramètres fiscaux et de contrôle, datées (EF-ADM-05, EF-ADM-06). Les autres
 * modules lisent par {@link #valeur(String)} et ses variantes typées ; ils ne connaissent ni la
 * table ni l'historique.
 */
@Service
public class ParametreService {

	private final ParametreRepository parametres;
	private final UtilisateurRepository utilisateurs;
	private final JournalAudit journal;
	private final Clock horloge;

	ParametreService(ParametreRepository parametres, UtilisateurRepository utilisateurs, JournalAudit journal, Clock horloge) {
		this.parametres = parametres;
		this.utilisateurs = utilisateurs;
		this.journal = journal;
		this.horloge = horloge;
	}

	/** Valeur en vigueur aujourd'hui, ou vide si le paramètre n'est pas encore renseigné. */
	@Transactional(readOnly = true)
	public Optional<String> valeur(String cle) {
		return valeurAu(cle, LocalDate.now(horloge));
	}

	@Transactional(readOnly = true)
	public Optional<String> valeurAu(String cle, LocalDate date) {
		definir(cle);
		Optional<String> fixee = parametres.findByCleOrderByDateEffetDesc(cle).stream()
				.filter(p -> !p.getDateEffet().isAfter(date)).findFirst().map(Parametre::getValeur);
		// Une politique (séparation des tâches…) garde sa valeur sûre tant que personne ne l'a réglée ; un seuil, jamais.
		return fixee.isPresent() ? fixee : Optional.ofNullable(definir(cle).defaut());
	}

	@Transactional(readOnly = true)
	public Optional<Boolean> booleen(String cle) {
		return valeur(cle).map(Boolean::parseBoolean);
	}

	@Transactional(readOnly = true)
	public Optional<Long> entier(String cle) {
		return valeur(cle).map(Long::valueOf);
	}

	@Transactional(readOnly = true)
	public Optional<BigDecimal> decimal(String cle) {
		return valeur(cle).map(BigDecimal::new);
	}

	@Transactional(readOnly = true)
	public List<ParametreDto> lister() {
		LocalDate aujourdhui = LocalDate.now(horloge);
		Map<String, List<Parametre>> parCle = new LinkedHashMap<>();
		parametres.findAllByOrderByCleAscDateEffetAsc().forEach(p -> parCle.computeIfAbsent(p.getCle(), k -> new ArrayList<>()).add(p));

		return CatalogueParametres.definitions().stream().map(d -> {
			List<Parametre> versions = parCle.getOrDefault(d.cle(), List.of());
			Parametre courante = versions.stream().filter(p -> !p.getDateEffet().isAfter(aujourdhui)).reduce((a, b) -> b).orElse(null);
			Parametre prochaine = versions.stream().filter(p -> p.getDateEffet().isAfter(aujourdhui)).findFirst().orElse(null);
			Parametre derniere = versions.isEmpty() ? null : versions.stream().max(java.util.Comparator.comparing(Parametre::getCreeLe)).orElseThrow();
			return new ParametreDto(d.cle(), d.groupe(), d.libelle(), d.aide(), d.type(), d.unite(), d.min(), d.max(), d.defaut(),
					courante != null, courante == null ? null : courante.getValeur(), courante == null ? null : courante.getDateEffet(),
					prochaine == null ? null : new ParametreDto.Prochaine(prochaine.getValeur(), prochaine.getDateEffet()),
					derniere == null ? null : ActeurDto.de(derniere.getCreePar()), derniere == null ? null : derniere.getCreeLe());
		}).toList();
	}

	@Transactional(readOnly = true)
	public List<ParametreDto.Version> historique(String cle) {
		definir(cle);
		return parametres.findByCleOrderByDateEffetDesc(cle).stream().map(ParametreDto.Version::de).toList();
	}

	@Transactional
	public void modifier(String cle, ParametreRequete requete) {
		DefinitionParametre definition = definir(cle);
		LocalDate aujourdhui = LocalDate.now(horloge);
		LocalDate dateEffet = requete.dateEffet() == null ? aujourdhui : requete.dateEffet();
		if (dateEffet.isBefore(aujourdhui)) {
			throw new ErreurValidationException("date_effet", "La date d'effet ne peut pas être dans le passé.");
		}
		String valeur = normaliser(definition, requete.valeur().trim());

		Optional<Parametre> existante = parametres.findByCleAndDateEffet(cle, dateEffet);
		Optional<String> avant = valeurAu(cle, dateEffet);
		if (existante.isPresent()) {
			existante.get().setValeur(valeur);
		} else {
			parametres.save(new Parametre(cle, valeur, dateEffet, acteur()));
		}
		parametres.flush();

		Map<String, Object> ancienne = new LinkedHashMap<>();
		ancienne.put("valeur", avant.orElse(null));
		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, "parametre.modifie", "Parametre", cle)
				.avecValeurs(ancienne, Map.of("valeur", valeur, "date_effet", dateEffet.toString())));
	}

	private static DefinitionParametre definir(String cle) {
		return CatalogueParametres.definition(cle)
				.orElseThrow(() -> new ErreurMetierException("PARAMETRE_INCONNU", "Paramètre inconnu : " + cle + ".", HttpStatus.NOT_FOUND));
	}

	/** Contrôle le type et les bornes, puis renvoie la forme stockée. */
	private static String normaliser(DefinitionParametre d, String valeur) {
		try {
			switch (d.type()) {
				case ENTIER -> {
					long n = Long.parseLong(valeur);
					verifierBornes(d, BigDecimal.valueOf(n));
					return Long.toString(n);
				}
				case DECIMAL -> {
					BigDecimal n = new BigDecimal(valeur.replace(',', '.'));
					verifierBornes(d, n);
					return n.stripTrailingZeros().toPlainString();
				}
				case DATE -> {
					return LocalDate.parse(valeur).toString();
				}
				case BOOLEEN -> {
					String v = valeur.toLowerCase(Locale.ROOT);
					if (Set.of("oui", "true", "1").contains(v)) {
						return "true";
					}
					if (Set.of("non", "false", "0").contains(v)) {
						return "false";
					}
					throw new ErreurValidationException("valeur", "Répondez « oui » ou « non ».");
				}
				default -> {
					if (valeur.length() < 3) {
						throw new ErreurValidationException("valeur", "Valeur trop courte.");
					}
					return valeur;
				}
			}
		} catch (NumberFormatException | DateTimeParseException e) {
			throw new ErreurValidationException("valeur", switch (d.type()) {
				case ENTIER -> "Un nombre entier est attendu.";
				case DECIMAL -> "Un nombre est attendu.";
				case BOOLEEN, TEXTE -> "Valeur invalide.";
				default -> "Une date au format AAAA-MM-JJ est attendue.";
			});
		}
	}

	private static void verifierBornes(DefinitionParametre d, BigDecimal n) {
		if ((d.min() != null && n.compareTo(d.min()) < 0) || (d.max() != null && n.compareTo(d.max()) > 0)) {
			throw new ErreurValidationException("valeur", "La valeur doit être comprise entre " + d.min().toPlainString()
					+ " et " + d.max().toPlainString() + (d.unite() == null ? "" : " " + d.unite()) + ".");
		}
	}

	private Utilisateur acteur() {
		return ActeurAuthentifie.courant().flatMap(a -> utilisateurs.findById(a.id())).orElse(null);
	}

}
