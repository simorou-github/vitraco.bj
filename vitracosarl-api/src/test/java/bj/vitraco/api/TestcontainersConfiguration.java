package bj.vitraco.api;

import bj.vitraco.api.adm.auth.DestinataireCompte;
import bj.vitraco.api.adm.auth.EnvoiLienCompte;
import bj.vitraco.api.adm.emplacements.VerificationUsageEmplacement;
import bj.vitraco.api.app.ConditionEtapeCommande;
import bj.vitraco.api.app.StatutCommande;
import bj.vitraco.api.cat.chassis.VerificationCorrectionChassis;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
	}

	/** Garde les derniers liens « envoyés » pour que les tests puissent les suivre. */
	public static class LiensCaptures implements EnvoiLienCompte {

		private final Map<String, String> activation = new ConcurrentHashMap<>();
		private final Map<String, String> reinitialisation = new ConcurrentHashMap<>();

		@Override
		public void envoyerActivation(DestinataireCompte destinataire, String lien, Duration validite) {
			activation.put(destinataire.email(), lien);
		}

		@Override
		public void envoyerReinitialisation(DestinataireCompte destinataire, String lien, Duration validite) {
			reinitialisation.put(destinataire.email(), lien);
		}

		public String jetonActivation(String email) {
			return jeton(activation.get(email));
		}

		public String jetonReinitialisation(String email) {
			return jeton(reinitialisation.get(email));
		}

		private static String jeton(String lien) {
			return lien == null ? null : lien.substring(lien.indexOf("token=") + 6);
		}

	}

	@Bean
	@Primary
	LiensCaptures liensCaptures() {
		return new LiensCaptures();
	}

	/** Tient lieu du module STK, absent pour l'instant : les tests déclarent eux-mêmes les emplacements « utilisés ». */
	public static class UsageSimule implements VerificationUsageEmplacement {

		private final Set<Long> enUsage = ConcurrentHashMap.newKeySet();

		public void marquerEnUsage(Long emplacementId) {
			enUsage.add(emplacementId);
		}

		public void liberer(Long emplacementId) {
			enUsage.remove(emplacementId);
		}

		@Override
		public boolean aStockOuOperationEnCours(Long emplacementId) {
			return enUsage.contains(emplacementId);
		}

	}

	@Bean
	UsageSimule usageSimule() {
		return new UsageSimule();
	}

	/** Tient lieu de la facturation, absente pour l'instant : les tests déclarent eux-mêmes les châssis « facturés ». */
	public static class FacturesSimulees implements VerificationCorrectionChassis {

		private final Set<Long> normalises = ConcurrentHashMap.newKeySet();

		public void normaliser(Long chassisId) {
			normalises.add(chassisId);
		}

		@Override
		public Optional<String> refus(Long chassisId) {
			return normalises.contains(chassisId) ? Optional.of("il figure sur une facture normalisée.") : Optional.empty();
		}

	}

	@Bean
	FacturesSimulees facturesSimulees() {
		return new FacturesSimulees();
	}

	/** Tient lieu des arrivages, absents pour l'instant : les tests déclarent eux-mêmes les étapes qu'ils bloquent. */
	public static class ConditionsSimulees implements ConditionEtapeCommande {

		private final Map<Long, StatutCommande> bloquees = new ConcurrentHashMap<>();

		public void bloquer(Long commandeId, StatutCommande cible) {
			bloquees.put(commandeId, cible);
		}

		@Override
		public Optional<String> refus(Long commandeId, StatutCommande cible) {
			return bloquees.get(commandeId) == cible ? Optional.of("aucun arrivage n'est enregistré.") : Optional.empty();
		}

	}

	@Bean
	ConditionsSimulees conditionsSimulees() {
		return new ConditionsSimulees();
	}

}
