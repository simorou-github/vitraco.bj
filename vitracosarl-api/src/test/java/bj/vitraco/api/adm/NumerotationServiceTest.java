package bj.vitraco.api.adm;

import bj.vitraco.api.TestcontainersConfiguration;
import bj.vitraco.api.adm.numerotation.NumerotationService;
import bj.vitraco.api.adm.numerotation.TypeDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Year;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class NumerotationServiceTest {

	@Autowired
	NumerotationService numerotation;
	@Autowired
	TransactionTemplate tx;
	@Autowired
	Clock horloge;

	private String emplacementUnique() {
		return "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
	}

	private String numero(TypeDocument type, String emplacement) {
		return tx.execute(s -> numerotation.prochainNumero(type, emplacement));
	}

	@Test
	void leNumeroPorteLePrefixeLEmplacementLAnneeEtUnCompteurSurSixChiffres() {
		String empl = emplacementUnique();
		int annee = Year.now(horloge).getValue();

		assertThat(numero(TypeDocument.FACTURE_VENTE, empl)).isEqualTo("FV-" + empl + "-" + annee + "-000001");
		assertThat(numero(TypeDocument.FACTURE_VENTE, empl)).isEqualTo("FV-" + empl + "-" + annee + "-000002");
	}

	@Test
	void unDocumentSansEmplacementNeContientPasDeSegmentEmplacement() {
		String numero = tx.execute(s -> numerotation.prochainNumero(TypeDocument.COMMANDE_FOURNISSEUR, null));
		assertThat(numero).matches("PO-" + Year.now(horloge).getValue() + "-\\d{6}");
	}

	@Test
	void lesCompteursSontIndependantsParTypeEtParEmplacement() {
		String a = emplacementUnique(), b = emplacementUnique();

		assertThat(numero(TypeDocument.LIVRAISON, a)).endsWith("-000001");
		assertThat(numero(TypeDocument.LIVRAISON, a)).endsWith("-000002");
		assertThat(numero(TypeDocument.LIVRAISON, b)).endsWith("-000001");
		assertThat(numero(TypeDocument.RECU_REGLEMENT, a)).endsWith("-000001");
	}

	@Test
	void uneTransactionAnnuleeRendLeNumero() {
		String empl = emplacementUnique();

		tx.executeWithoutResult(s -> {
			assertThat(numerotation.prochainNumero(TypeDocument.TRANSFERT, empl)).endsWith("-000001");
			s.setRollbackOnly();
		});

		assertThat(numero(TypeDocument.TRANSFERT, empl)).endsWith("-000001");
	}

	@Test
	void lAppelHorsTransactionEstRefusePourNePasPerdreDeNumero() {
		assertThatThrownBy(() -> numerotation.prochainNumero(TypeDocument.AVOIR, null))
				.isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void vingtDemandesSimultaneesObtiennentVingtNumerosDistinctsSansTrou() throws Exception {
		String empl = emplacementUnique();

		List<String> numeros;
		try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
			List<Future<String>> taches = IntStream.range(0, 20)
					.mapToObj(i -> pool.submit(() -> numero(TypeDocument.INVENTAIRE, empl))).toList();
			numeros = taches.stream().map(t -> {
				try {
					return t.get();
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			}).toList();
		}

		assertThat(numeros).doesNotHaveDuplicates();
		assertThat(numeros.stream().map(n -> Integer.parseInt(n.substring(n.lastIndexOf('-') + 1))).sorted())
				.containsExactlyElementsOf(IntStream.rangeClosed(1, 20).boxed().toList());
	}

}
