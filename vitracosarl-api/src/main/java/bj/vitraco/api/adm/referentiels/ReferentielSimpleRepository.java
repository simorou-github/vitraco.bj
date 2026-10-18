package bj.vitraco.api.adm.referentiels;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.query.Param;

@NoRepositoryBean
public interface ReferentielSimpleRepository<E extends ReferentielSimple> extends JpaRepository<E, Long>, JpaSpecificationExecutor<E> {

	@Query("select count(e) > 0 from #{#entityName} e where upper(e.code) = upper(:code)")
	boolean codeUtilise(@Param("code") String code);

}
