package bj.vitraco.api.adm.emplacements;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EmplacementRepository extends JpaRepository<Emplacement, Long>, JpaSpecificationExecutor<Emplacement> {

	@Query("select e from Emplacement e where upper(e.code) = upper(:code)")
	Optional<Emplacement> findByCode(@Param("code") String code);

	@Query("select count(e) > 0 from Emplacement e where upper(e.code) = upper(:code)")
	boolean codeUtilise(@Param("code") String code);

	List<Emplacement> findByIdIn(Collection<Long> ids);

	List<Emplacement> findByActifTrue();

	boolean existsByMagasinRattachementIdAndActifTrue(Long magasinId);

}
