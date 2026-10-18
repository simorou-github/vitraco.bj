package bj.vitraco.api.common;

import java.util.List;
import java.util.Map;

/** Erreur sur un champ précis du formulaire (par exemple un e-mail déjà utilisé), renvoyée comme une erreur de validation. */
public class ErreurValidationException extends RuntimeException {

	private final transient Map<String, List<String>> erreurs;

	public ErreurValidationException(String champ, String message) {
		super(message);
		this.erreurs = Map.of(champ, List.of(message));
	}

	public Map<String, List<String>> getErreurs() {
		return erreurs;
	}

}
