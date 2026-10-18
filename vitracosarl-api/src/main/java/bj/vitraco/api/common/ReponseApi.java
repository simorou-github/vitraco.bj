package bj.vitraco.api.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Enveloppe de toute réponse réussie : {@code { "message": ..., "data": ... }}. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReponseApi<T>(String message, T data) {

	public static <T> ReponseApi<T> de(T data) {
		return new ReponseApi<>(null, data);
	}

	public static <T> ReponseApi<T> de(String message, T data) {
		return new ReponseApi<>(message, data);
	}

}
