package bj.vitraco.api.cat.chassis;

import java.util.Locale;
import java.util.regex.Pattern;

/** Forme normalisée d'un numéro de châssis : majuscules, sans espace ni tiret (RG-01). */
public final class NumeroChassis {

	private static final Pattern VALIDE = Pattern.compile("^[A-Z0-9]{3,40}$");

	private NumeroChassis() {
	}

	public static String normaliser(String saisie) {
		return saisie == null ? "" : saisie.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
	}

	public static boolean valide(String normalise) {
		return VALIDE.matcher(normalise).matches();
	}

}
