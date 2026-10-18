package bj.vitraco.api.adm.parametres;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ParametreRepository extends JpaRepository<Parametre, Long> {

	List<Parametre> findByCleOrderByDateEffetDesc(String cle);

	Optional<Parametre> findByCleAndDateEffet(String cle, LocalDate dateEffet);

	List<Parametre> findAllByOrderByCleAscDateEffetAsc();

}
