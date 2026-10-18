package bj.vitraco.api.adm.auth;

import bj.vitraco.api.common.ErreurValidationException;

import java.util.ArrayList;
import java.util.List;

/** Politique de mot de passe (EF-ADM-10) : longueur minimale, majuscule, minuscule, chiffre et symbole. */
public final class PolitiqueMotDePasse {

	private PolitiqueMotDePasse() {
	}

	public static List<String> violations(String motDePasse, int longueurMin) {
		List<String> violations = new ArrayList<>();
		if (motDePasse == null || motDePasse.length() < longueurMin) {
			violations.add("au moins " + longueurMin + " caractères");
		}
		if (motDePasse == null) {
			return violations;
		}
		if (motDePasse.chars().noneMatch(Character::isUpperCase)) {
			violations.add("une majuscule");
		}
		if (motDePasse.chars().noneMatch(Character::isLowerCase)) {
			violations.add("une minuscule");
		}
		if (motDePasse.chars().noneMatch(Character::isDigit)) {
			violations.add("un chiffre");
		}
		if (motDePasse.chars().allMatch(c -> Character.isLetterOrDigit(c))) {
			violations.add("un symbole");
		}
		return violations;
	}

	public static void exiger(String motDePasse, int longueurMin, String champ) {
		List<String> violations = violations(motDePasse, longueurMin);
		if (!violations.isEmpty()) {
			throw new ErreurValidationException(champ, "Le mot de passe doit contenir " + String.join(", ", violations) + ".");
		}
	}

}
