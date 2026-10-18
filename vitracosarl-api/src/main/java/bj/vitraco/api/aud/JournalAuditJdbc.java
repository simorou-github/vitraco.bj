package bj.vitraco.api.aud;

import org.springframework.jdbc.core.simple.JdbcClient;
import bj.vitraco.api.common.security.ActeurAuthentifie;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

import java.util.OptionalLong;

@Service
class JournalAuditJdbc implements JournalAudit {

	private final JdbcClient jdbc;
	private final JsonMapper json;

	JournalAuditJdbc(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void enregistrer(EntreeAudit e) {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		Long utilisateurId = e.acteurId() != null ? e.acteurId() : ActeurAuthentifie.courant().map(ActeurAuthentifie::id).orElse(null);
		String libelle = e.acteurLibelle() != null ? e.acteurLibelle() : ActeurAuthentifie.courant().map(ActeurAuthentifie::libelle)
				.orElse(auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName()) ? auth.getName() : null);

		jdbc.sql("""
						INSERT INTO journal_audit (utilisateur_id, utilisateur_libelle, action, module, objet_type, objet_id,
						                           ancienne_valeur, nouvelle_valeur, motif, adresse_ip)
						VALUES (:utilisateurId, :libelle, :action, :module, :objetType, :objetId,
						        CAST(:ancienne AS jsonb), CAST(:nouvelle AS jsonb), :motif, :ip)
						""")
				.param("utilisateurId", utilisateurId)
				.param("libelle", libelle)
				.param("action", e.action())
				.param("module", e.module().name())
				.param("objetType", e.objetType())
				.param("objetId", e.objetId())
				.param("ancienne", enJson(e.ancienneValeur()))
				.param("nouvelle", enJson(e.nouvelleValeur()))
				.param("motif", e.motif())
				.param("ip", adresseIp())
				.update();
	}

	@Override
	@Transactional(readOnly = true)
	public OptionalLong premiereAlteration() {
		// 0 = chaîne intacte (les rangs commencent à 1).
		long rang = jdbc.sql("SELECT coalesce(journal_audit_premiere_alteration(), 0)").query(Long.class).single();
		return rang == 0 ? OptionalLong.empty() : OptionalLong.of(rang);
	}

	private String enJson(Object valeur) {
		return valeur == null ? null : json.writeValueAsString(valeur);
	}

	private static String adresseIp() {
		RequestAttributes attributs = RequestContextHolder.getRequestAttributes();
		return attributs instanceof ServletRequestAttributes servlet ? servlet.getRequest().getRemoteAddr() : null;
	}

}
