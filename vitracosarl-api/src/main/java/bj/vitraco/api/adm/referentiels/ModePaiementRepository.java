package bj.vitraco.api.adm.referentiels;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ModePaiementRepository extends JpaRepository<ModePaiement, Long>, JpaSpecificationExecutor<ModePaiement> {

	@Query("select count(m) > 0 from ModePaiement m where upper(m.code) = upper(:code)")
	boolean codeUtilise(@Param("code") String code);

}
