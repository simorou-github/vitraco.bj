package bj.vitraco.api.cat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ModeleRepository extends JpaRepository<Modele, Long>, JpaSpecificationExecutor<Modele> {

	@Query("select m from Modele m where upper(m.codeArticle) = upper(:code)")
	java.util.Optional<Modele> findByCodeArticle(@Param("code") String code);

	@Query("select count(m) > 0 from Modele m where upper(m.codeArticle) = upper(:code)")
	boolean codeArticleUtilise(@Param("code") String code);

}
