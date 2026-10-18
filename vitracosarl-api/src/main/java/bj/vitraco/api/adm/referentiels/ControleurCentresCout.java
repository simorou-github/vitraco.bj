package bj.vitraco.api.adm.referentiels;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/centres-cout")
class ControleurCentresCout extends ControleurReferentielSimple<CentreCout> {

	ControleurCentresCout(ServiceCentresCout service) {
		super(service);
	}

}
