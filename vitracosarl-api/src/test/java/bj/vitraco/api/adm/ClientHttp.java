package bj.vitraco.api.adm;

import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.LinkedHashMap;
import java.util.Map;


/** Un navigateur de test : garde ses cookies (session, jeton CSRF) d'une requête à l'autre. */
class ClientHttp {

	private final MockMvc mvc;
	private final Map<String, String> cookies = new LinkedHashMap<>();

	ClientHttp(MockMvc mvc) {
		this.mvc = mvc;
	}

	ResultActions get(String url) throws Exception {
		return envoyer(MockMvcRequestBuilders.get(url));
	}

	ResultActions post(String url, String json) throws Exception {
		return envoyer(MockMvcRequestBuilders.post(url).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	ResultActions put(String url, String json) throws Exception {
		return envoyer(MockMvcRequestBuilders.put(url).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	/** Téléverse un fichier (champ « fichier ») avec des paramètres de formulaire éventuels. */
	ResultActions postFichier(String url, String nomFichier, byte[] contenu, String... parametres) throws Exception {
		var requete = MockMvcRequestBuilders.multipart(url).file(new org.springframework.mock.web.MockMultipartFile("fichier", nomFichier, "application/octet-stream", contenu));
		for (int i = 0; i + 1 < parametres.length; i += 2) {
			requete.param(parametres[i], parametres[i + 1]);
		}
		return envoyer(requete);
	}

	ResultActions delete(String url) throws Exception {
		return envoyer(MockMvcRequestBuilders.delete(url));
	}

	/** Sans jeton CSRF, pour vérifier qu'une écriture est refusée. */
	ResultActions postSansCsrf(String url, String json) throws Exception {
		MockHttpServletRequestBuilder requete = MockMvcRequestBuilders.post(url).contentType(MediaType.APPLICATION_JSON).content(json);
		return mvc.perform(avecCookies(requete));
	}

	private ResultActions envoyer(org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder<?> requete) throws Exception {
		if (!cookies.containsKey("XSRF-TOKEN")) {
			memoriser(mvc.perform(avecCookies(MockMvcRequestBuilders.get("/api/v1/auth/csrf"))));
		}
		String jeton = cookies.get("XSRF-TOKEN");
		if (jeton != null) {
			requete.header("X-XSRF-TOKEN", jeton);
		}
		ResultActions resultat = mvc.perform(avecCookies(requete));
		memoriser(resultat);
		return resultat;
	}

	private org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder<?> avecCookies(org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder<?> requete) {
		cookies.forEach((nom, valeur) -> requete.cookie(new Cookie(nom, valeur)));
		return requete;
	}

	private void memoriser(ResultActions resultat) {
		for (Cookie c : resultat.andReturn().getResponse().getCookies()) {
			if (c.getMaxAge() == 0) {
				cookies.remove(c.getName());
			} else {
				cookies.put(c.getName(), c.getValue());
			}
		}
	}

}
