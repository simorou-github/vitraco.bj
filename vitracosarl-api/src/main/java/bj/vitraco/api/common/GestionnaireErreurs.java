package bj.vitraco.api.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GestionnaireErreurs {

	private static final Logger log = LoggerFactory.getLogger(GestionnaireErreurs.class);

	@ExceptionHandler(ErreurMetierException.class)
	ResponseEntity<ReponseErreur> erreurMetier(ErreurMetierException e) {
		return ResponseEntity.status(e.getStatut()).body(ReponseErreur.of(e.getCode(), e.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ReponseErreur> validation(MethodArgumentNotValidException e) {
		Map<String, List<String>> erreurs = new LinkedHashMap<>();
		e.getBindingResult().getFieldErrors().forEach(f ->
				erreurs.computeIfAbsent(enSnakeCase(f.getField()), k -> new ArrayList<>()).add(f.getDefaultMessage()));
		return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
				.body(new ReponseErreur("Les données envoyées sont invalides.", "VALIDATION_ERROR", erreurs));
	}

	@ExceptionHandler(ErreurValidationException.class)
	ResponseEntity<ReponseErreur> validationChamp(ErreurValidationException e) {
		return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
				.body(new ReponseErreur("Les données envoyées sont invalides.", "VALIDATION_ERROR", e.getErreurs()));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ReponseErreur> corpsIllisible(HttpMessageNotReadableException e) {
		return ResponseEntity.badRequest()
				.body(ReponseErreur.of("CORPS_INVALIDE", "Le corps de la requête est illisible ou incomplet."));
	}

	/** Un paramètre d'URL de mauvais type ou hors liste (par exemple un statut inconnu) est une erreur du client, pas du serveur. */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ReponseErreur> parametreInvalide(MethodArgumentTypeMismatchException e) {
		return ResponseEntity.badRequest()
				.body(ReponseErreur.of("PARAMETRE_INVALIDE", "Valeur invalide pour le paramètre « " + e.getName() + " »."));
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ReponseErreur> accesRefuse(AccessDeniedException e) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(ReponseErreur.of("ACCES_REFUSE", "Vous n'avez pas le droit d'effectuer cette action."));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ReponseErreur> inattendue(Exception e) {
		if (e instanceof ErrorResponse reponse && reponse.getStatusCode().is4xxClientError()) {
			// Route inconnue, méthode non permise… : Spring connaît déjà le bon état HTTP.
			return ResponseEntity.status(reponse.getStatusCode())
					.body(ReponseErreur.of("REQUETE_INVALIDE", "La requête ne peut pas être traitée."));
		}
		log.error("Erreur inattendue", e);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ReponseErreur.of("ERREUR_INTERNE", "Une erreur interne est survenue."));
	}

	/** Les clés d'erreur suivent le JSON de l'API (snake_case), pas les noms Java : « typeDgi » devient « type_dgi ». */
	static String enSnakeCase(String champ) {
		return champ.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
	}

}
