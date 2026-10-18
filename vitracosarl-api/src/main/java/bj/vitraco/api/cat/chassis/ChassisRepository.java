package bj.vitraco.api.cat.chassis;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface ChassisRepository extends JpaRepository<Chassis, Long>, JpaSpecificationExecutor<Chassis> {

	Optional<Chassis> findByNumero(String numero);

	java.util.List<Chassis> findByArrivageIdAndStatut(Long arrivageId, StatutChassis statut);

}
