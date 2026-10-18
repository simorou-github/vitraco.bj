package bj.vitraco.api.adm.referentiels;

import bj.vitraco.api.adm.utilisateurs.Utilisateur;
import bj.vitraco.api.adm.utilisateurs.UtilisateurRepository;
import bj.vitraco.api.common.security.ActeurAuthentifie;

import java.util.Locale;

/** Petits utilitaires partagés par les services de ce paquet. */
final class Acteurs {

	private Acteurs() {
	}

	static Utilisateur courant(UtilisateurRepository utilisateurs) {
		return ActeurAuthentifie.courant().flatMap(a -> utilisateurs.findById(a.id())).orElse(null);
	}

	static String code(String valeur) {
		return valeur.trim().toUpperCase(Locale.ROOT);
	}

	static String vide(String valeur) {
		return valeur == null || valeur.isBlank() ? null : valeur.trim();
	}

}
