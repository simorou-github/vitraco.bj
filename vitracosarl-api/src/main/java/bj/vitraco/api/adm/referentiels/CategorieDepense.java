package bj.vitraco.api.adm.referentiels;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "categories_depense")
public class CategorieDepense extends ReferentielSimple {

	protected CategorieDepense() {
	}

	public CategorieDepense(String code, String libelle) {
		super(code, libelle);
	}

}
