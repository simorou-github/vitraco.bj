package bj.vitraco.api.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/** Corps JSON de toute erreur : {@code message}, {@code code}, et {@code errors} pour les champs invalides. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReponseErreur(String message, String code, Map<String, List<String>> errors) {

	public static ReponseErreur of(String code, String message) {
		return new ReponseErreur(message, code, null);
	}

}
