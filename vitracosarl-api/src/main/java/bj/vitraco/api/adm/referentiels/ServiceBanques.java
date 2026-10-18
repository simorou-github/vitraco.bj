package bj.vitraco.api.adm.referentiels;

import bj.vitraco.api.adm.utilisateurs.UtilisateurRepository;
import bj.vitraco.api.aud.JournalAudit;
import org.springframework.stereotype.Service;

@Service
class ServiceBanques extends ServiceReferentielSimple<Banque> {

	ServiceBanques(BanqueRepository repository, UtilisateurRepository utilisateurs, JournalAudit journal) {
		super(repository, utilisateurs, journal, "Banque", "banque");
	}

	@Override
	protected Banque nouveau(String code, String libelle) {
		return new Banque(code, libelle);
	}

}
