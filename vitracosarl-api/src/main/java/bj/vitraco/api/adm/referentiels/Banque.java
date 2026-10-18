package bj.vitraco.api.adm.referentiels;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "banques")
public class Banque extends ReferentielSimple {

	protected Banque() {
	}

	public Banque(String code, String libelle) {
		super(code, libelle);
	}

}
