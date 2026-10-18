package bj.vitraco.api.adm.referentiels;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FournisseurRepository extends JpaRepository<Fournisseur, Long>, JpaSpecificationExecutor<Fournisseur> {

	@Query("select count(f) > 0 from Fournisseur f where lower(f.raisonSociale) = lower(:raisonSociale) and (:exclu is null or f.id <> :exclu)")
	boolean raisonSocialeUtilisee(@Param("raisonSociale") String raisonSociale, @Param("exclu") Long exclu);

}
