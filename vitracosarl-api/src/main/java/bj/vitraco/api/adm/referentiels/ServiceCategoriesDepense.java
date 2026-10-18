package bj.vitraco.api.adm.referentiels;

import bj.vitraco.api.adm.utilisateurs.UtilisateurRepository;
import bj.vitraco.api.aud.JournalAudit;
import org.springframework.stereotype.Service;

@Service
class ServiceCategoriesDepense extends ServiceReferentielSimple<CategorieDepense> {

	ServiceCategoriesDepense(CategorieDepenseRepository repository, UtilisateurRepository utilisateurs, JournalAudit journal) {
		super(repository, utilisateurs, journal, "Catégorie de dépense", "categorie_depense");
	}

	@Override
	protected CategorieDepense nouveau(String code, String libelle) {
		return new CategorieDepense(code, libelle);
	}

}
