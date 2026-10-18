package bj.vitraco.api.app;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CommandeFournisseurRepository extends JpaRepository<CommandeFournisseur, Long>, JpaSpecificationExecutor<CommandeFournisseur> {
}
