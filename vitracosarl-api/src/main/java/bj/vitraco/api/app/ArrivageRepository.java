package bj.vitraco.api.app;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ArrivageRepository extends JpaRepository<Arrivage, Long>, JpaSpecificationExecutor<Arrivage> {

	List<Arrivage> findByCommandeIdOrderById(Long commandeId);

	@Query("select count(a) > 0 from Arrivage a where a.commande.id = :commande and upper(a.numeroBl) = upper(:bl) and (:exclu is null or a.id <> :exclu)")
	boolean blUtilise(@Param("commande") Long commandeId, @Param("bl") String numeroBl, @Param("exclu") Long exclu);

}
