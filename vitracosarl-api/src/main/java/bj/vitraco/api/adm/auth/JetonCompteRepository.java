package bj.vitraco.api.adm.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface JetonCompteRepository extends JpaRepository<JetonCompte, Long> {

	Optional<JetonCompte> findByEmpreinteAndType(String empreinte, TypeJeton type);

	@Modifying
	@Query("update JetonCompte j set j.utiliseLe = :maintenant where j.utilisateur.id = :utilisateurId and j.type = :type and j.utiliseLe is null")
	void invaliderLesAnciens(@Param("utilisateurId") Long utilisateurId, @Param("type") TypeJeton type, @Param("maintenant") Instant maintenant);

}
