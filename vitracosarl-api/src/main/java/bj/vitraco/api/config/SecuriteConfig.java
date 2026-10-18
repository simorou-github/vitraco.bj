package bj.vitraco.api.config;

import bj.vitraco.api.adm.auth.MessagerieProprietes;
import bj.vitraco.api.notif.NotifProprietes;
import bj.vitraco.api.adm.auth.SecuriteProprietes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Sécurité de l'API : session par cookie, jeton CSRF en cookie lisible par le navigateur
 * (le front le renvoie dans l'en-tête X-XSRF-TOKEN), droits vérifiés côté serveur sur chaque
 * appel (EF-ADM-12).
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties({SecuriteProprietes.class, MessagerieProprietes.class, NotifProprietes.class})
class SecuriteConfig {

	@Bean
	SecurityFilterChain filtres(HttpSecurity http, SecurityContextRepository contextes) {
		http
				.securityContext(c -> c.securityContextRepository(contextes))
				.csrf(csrf -> csrf
						.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
						.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
				.addFilterAfter(new FiltreCookieCsrf(), BasicAuthenticationFilter.class)
				.cors(cors -> {
				})
				.authorizeHttpRequests(a -> a
						.requestMatchers("/actuator/health/**").permitAll()
						.requestMatchers("/api/v1/auth/csrf", "/api/v1/auth/login", "/api/v1/auth/logout",
								"/api/v1/auth/activation", "/api/v1/auth/mot-de-passe/**", "/api/v1/auth/2fa/**").permitAll()
						.anyRequest().authenticated())
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())
				.exceptionHandling(e -> e
						.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
						.accessDeniedHandler((requete, reponse, ex) -> {
							reponse.setStatus(HttpStatus.FORBIDDEN.value());
							reponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
							reponse.setCharacterEncoding("UTF-8");
							reponse.getWriter().write("{\"message\":\"Vous n'avez pas le droit d'effectuer cette action.\",\"code\":\"ACCES_REFUSE\"}");
						}));
		return http.build();
	}

	@Bean
	SecurityContextRepository contextesDeSecurite() {
		return new HttpSessionSecurityContextRepository();
	}

	@Bean
	PasswordEncoder encodeurMotDePasse() {
		return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
	}

	/** Aucun utilisateur en mémoire : les comptes viennent de la base, via {@code AuthService}. */
	@Bean
	UserDetailsService aucunUtilisateurEnMemoire() {
		return new InMemoryUserDetailsManager();
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(@Value("${vitraco.cors.origines:http://localhost:4200}") List<String> origines) {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(origines);
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(List.of("Content-Type", "Accept", "X-XSRF-TOKEN", "X-Requested-With", "Idempotency-Key", "X-Emplacement-Id"));
		configuration.setAllowCredentials(true);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", configuration);
		return source;
	}

	/** Force la création du cookie CSRF dès la première réponse, sans attendre qu'un contrôleur lise le jeton. */
	static final class FiltreCookieCsrf extends OncePerRequestFilter {

		@Override
		protected void doFilterInternal(HttpServletRequest requete, HttpServletResponse reponse, FilterChain chaine)
				throws ServletException, IOException {
			CsrfToken jeton = (CsrfToken) requete.getAttribute(CsrfToken.class.getName());
			if (jeton != null) {
				jeton.getToken();
			}
			chaine.doFilter(requete, reponse);
		}

	}

}
