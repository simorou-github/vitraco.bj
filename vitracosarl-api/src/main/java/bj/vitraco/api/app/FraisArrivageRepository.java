package bj.vitraco.api.app;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FraisArrivageRepository extends JpaRepository<FraisArrivage, Long> {

	List<FraisArrivage> findByArrivageIdOrderByDateFraisDescIdDesc(Long arrivageId);

}
