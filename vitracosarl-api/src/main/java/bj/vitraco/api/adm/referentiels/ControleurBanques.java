package bj.vitraco.api.adm.referentiels;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/banques")
class ControleurBanques extends ControleurReferentielSimple<Banque> {

	ControleurBanques(ServiceBanques service) {
		super(service);
	}

}
