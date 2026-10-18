package bj.vitraco.api.common;

import org.springframework.http.HttpStatus;

/**
 * Règle de gestion violée, à renvoyer telle quelle au client avec un code métier
 * stable (SRS §10.7), par exemple {@code CHASSIS_DEJA_RESERVE}.
 */
public class ErreurMetierException extends RuntimeException {

	private final String code;
	private final HttpStatus statut;

	public ErreurMetierException(String code, String message) {
		this(code, message, HttpStatus.UNPROCESSABLE_ENTITY);
	}

	public ErreurMetierException(String code, String message, HttpStatus statut) {
		super(message);
		this.code = code;
		this.statut = statut;
	}

	public String getCode() {
		return code;
	}

	public HttpStatus getStatut() {
		return statut;
	}

}
