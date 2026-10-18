package bj.vitraco.api.aud;

import bj.vitraco.api.TestcontainersConfiguration;
import bj.vitraco.api.common.CodeModule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class JournalAuditTest {

	@Autowired
	JournalAudit journal;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	TransactionTemplate tx;

	private void enAnnulantTout(Runnable travail) {
		tx.executeWithoutResult(statut -> {
			travail.run();
			statut.setRollbackOnly();
		});
	}

	private void ecrire(String action) {
		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, action, "Utilisateur", "42")
				.avecValeurs(Map.of("statut", "ACTIF"), Map.of("statut", "SUSPENDU"))
				.avecMotif("test"));
	}

	@Test
	void uneEntreeEstEnregistreeAvecSesValeursAvantEtApres() {
		enAnnulantTout(() -> {
			ecrire("utilisateur.suspendu");

			var ligne = jdbc.sql("""
					SELECT module, action, objet_id, ancienne_valeur->>'statut' AS avant,
					       nouvelle_valeur->>'statut' AS apres, motif
					FROM journal_audit ORDER BY rang DESC LIMIT 1
					""").query().singleRow();

			assertThat(ligne).containsEntry("module", "ADM")
					.containsEntry("action", "utilisateur.suspendu")
					.containsEntry("objet_id", "42")
					.containsEntry("avant", "ACTIF")
					.containsEntry("apres", "SUSPENDU")
					.containsEntry("motif", "test");
		});
	}

	@Test
	void lEnregistrementExigeUneTransactionPourEtreValideAvecLOperation() {
		assertThatThrownBy(() -> ecrire("hors.transaction"))
				.isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
	}

	@Test
	void lesEntreesSontChaineesEtLaChaineEstIntacte() {
		enAnnulantTout(() -> {
			ecrire("a");
			ecrire("b");
			ecrire("c");

			var hashs = jdbc.sql("SELECT hash_precedent, hash FROM journal_audit ORDER BY rang DESC LIMIT 3")
					.query().listOfRows();
			assertThat(hashs.get(0).get("hash_precedent")).isEqualTo(hashs.get(1).get("hash"));
			assertThat(hashs.get(1).get("hash_precedent")).isEqualTo(hashs.get(2).get("hash"));
			assertThat(journal.premiereAlteration()).isEmpty();
		});
	}

	@Test
	void modifierSupprimerOuViderLeJournalEstRefuse() {
		enAnnulantTout(() -> ecrire("x"));
		tx.executeWithoutResult(s -> ecrire("y"));

		assertThatThrownBy(() -> jdbc.sql("UPDATE journal_audit SET action = 'falsifie'").update())
				.isInstanceOf(DataAccessException.class).hasMessageContaining("ajout seul");
		assertThatThrownBy(() -> jdbc.sql("DELETE FROM journal_audit").update())
				.isInstanceOf(DataAccessException.class).hasMessageContaining("ajout seul");
		assertThatThrownBy(() -> jdbc.sql("TRUNCATE journal_audit").update())
				.isInstanceOf(DataAccessException.class).hasMessageContaining("ajout seul");
	}

	@Test
	void uneFalsificationContourneeParUnDroitDeProprietaireEstDetectee() {
		enAnnulantTout(() -> {
			ecrire("m");
			ecrire("n");
			// Un propriétaire de table pourrait désactiver le déclencheur : la chaîne doit le révéler.
			jdbc.sql("ALTER TABLE journal_audit DISABLE TRIGGER trg_journal_audit_ajout_seul").update();
			jdbc.sql("""
					UPDATE journal_audit SET motif = 'falsifie'
					WHERE rang = (SELECT max(rang) - 1 FROM journal_audit)
					""").update();

			assertThat(journal.premiereAlteration()).isPresent();
		});
	}

	@Test
	void desEcrituresConcurrentesGardentUneChaineSansTrou() throws Exception {
		int fils = 8, parFil = 5;
		long avant = jdbc.sql("SELECT count(*) FROM journal_audit").query(Long.class).single();

		try (ExecutorService pool = Executors.newFixedThreadPool(fils)) {
			var taches = IntStream.range(0, fils).mapToObj(f -> pool.submit(() ->
					IntStream.range(0, parFil).forEach(i -> tx.executeWithoutResult(s -> ecrire("concurrent-" + f + "-" + i))))).toList();
			for (Future<?> t : taches) {
				t.get();
			}
		}

		long apres = jdbc.sql("SELECT count(*) FROM journal_audit").query(Long.class).single();
		assertThat(apres - avant).isEqualTo((long) fils * parFil);
		assertThat(journal.premiereAlteration()).isEmpty();
	}

}
