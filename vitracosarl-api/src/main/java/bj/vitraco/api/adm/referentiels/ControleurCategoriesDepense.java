package bj.vitraco.api.adm.referentiels;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/categories-depense")
class ControleurCategoriesDepense extends ControleurReferentielSimple<CategorieDepense> {

	ControleurCategoriesDepense(ServiceCategoriesDepense service) {
		super(service);
	}

}
