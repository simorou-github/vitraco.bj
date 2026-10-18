package bj.vitraco.api.adm.referentiels;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "centres_cout")
public class CentreCout extends ReferentielSimple {

	protected CentreCout() {
	}

	public CentreCout(String code, String libelle) {
		super(code, libelle);
	}

}
