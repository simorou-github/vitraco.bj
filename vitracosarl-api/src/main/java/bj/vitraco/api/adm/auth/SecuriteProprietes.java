package bj.vitraco.api.adm.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Réglages de sécurité des comptes (EF-ADM-10), à terme repris dans les paramètres administrables (EF-ADM-06). */
@ConfigurationProperties("vitraco.securite")
public record SecuriteProprietes(
		@DefaultValue("5") int tentativesMax,
		@DefaultValue("15") int verrouillageMinutes,
		@DefaultValue("24") int activationValiditeHeures,
		@DefaultValue("60") int reinitialisationValiditeMinutes,
		@DefaultValue("10") int motDePasseLongueurMin,
		@DefaultValue("http://localhost:4200/#/authentication") String lienBaseUrl,
		/** Clé AES-256 (Base64, 32 octets) qui chiffre les secrets de double authentification. Obligatoire. */
		@DefaultValue("") String cleA2f,
		/** Durée pendant laquelle un mot de passe validé attend son second facteur. */
		@DefaultValue("10") int a2fAttenteMinutes,
		@DefaultValue("VITRACO Moto") String a2fEmetteur) {
}
