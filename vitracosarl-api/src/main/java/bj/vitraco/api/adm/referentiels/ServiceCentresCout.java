package bj.vitraco.api.adm.referentiels;

import bj.vitraco.api.adm.utilisateurs.UtilisateurRepository;
import bj.vitraco.api.aud.JournalAudit;
import org.springframework.stereotype.Service;

@Service
class ServiceCentresCout extends ServiceReferentielSimple<CentreCout> {

	ServiceCentresCout(CentreCoutRepository repository, UtilisateurRepository utilisateurs, JournalAudit journal) {
		super(repository, utilisateurs, journal, "Centre de coût", "centre_cout");
	}

	@Override
	protected CentreCout nouveau(String code, String libelle) {
		return new CentreCout(code, libelle);
	}

}
