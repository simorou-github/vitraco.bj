package bj.vitraco.api.adm.droits;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {

	Optional<Role> findByCode(String code);

	List<Role> findByCodeIn(Collection<String> codes);

	boolean existsByCode(String code);

}
