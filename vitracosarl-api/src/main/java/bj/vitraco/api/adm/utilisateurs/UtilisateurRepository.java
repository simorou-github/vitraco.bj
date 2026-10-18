package bj.vitraco.api.adm.utilisateurs;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UtilisateurRepository extends JpaRepository<Utilisateur, Long>, JpaSpecificationExecutor<Utilisateur> {

	@Query("select u from Utilisateur u where lower(u.email) = lower(:email)")
	Optional<Utilisateur> findByEmail(@Param("email") String email);

	@Query("select count(u) > 0 from Utilisateur u where lower(u.email) = lower(:email) and (:exclu is null or u.id <> :exclu)")
	boolean emailUtilise(@Param("email") String email, @Param("exclu") Long exclu);

	@Query("select u.email from Utilisateur u join u.roles r where r.id = :roleId")
	List<String> emailsDuRole(@Param("roleId") Long roleId);

	long countByRolesId(Long roleId);

}
