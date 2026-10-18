package bj.vitraco.api.adm;

import bj.vitraco.api.adm.droits.CatalogueDroits;
import bj.vitraco.api.adm.droits.Permission;
import bj.vitraco.api.adm.droits.PermissionRepository;
import bj.vitraco.api.adm.droits.Role;
import bj.vitraco.api.adm.droits.RoleRepository;
import bj.vitraco.api.adm.emplacements.Emplacement;
import bj.vitraco.api.adm.emplacements.EmplacementRepository;
import bj.vitraco.api.adm.emplacements.TypeEmplacement;
import bj.vitraco.api.adm.utilisateurs.StatutUtilisateur;
import bj.vitraco.api.adm.utilisateurs.Utilisateur;
import bj.vitraco.api.adm.utilisateurs.UtilisateurRepository;
import bj.vitraco.api.aud.EntreeAudit;
import bj.vitraco.api.aud.JournalAudit;
import bj.vitraco.api.common.CodeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Au démarrage : synchronise le catalogue des permissions, crée les rôles livrés par défaut
 * s'ils manquent (sans toucher aux rôles déjà réglés), puis amorce le premier administrateur
 * sur une base sans utilisateur.
 */
@Component
class InitialisationAdm implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(InitialisationAdm.class);

	private final PermissionRepository permissions;
	private final RoleRepository roles;
	private final EmplacementRepository emplacements;
	/** Création des rôles livrés : exigent-ils la double authentification (SRS 7.1) ? Faux seulement dans les tests. */
	private final boolean a2fRolesObligatoire;
	private final UtilisateurRepository utilisateurs;
	private final PasswordEncoder encodeur;
	private final JournalAudit journal;
	private final String emailAdmin;
	private final String motDePasseAdmin;

	InitialisationAdm(PermissionRepository permissions, RoleRepository roles, EmplacementRepository emplacements, UtilisateurRepository utilisateurs,
					  PasswordEncoder encodeur, JournalAudit journal,
					  @Value("${vitraco.amorcage.admin-email:admin@vitraco.bj}") String emailAdmin,
					  @Value("${vitraco.amorcage.admin-mot-de-passe:}") String motDePasseAdmin,
					  @Value("${vitraco.amorcage.a2f-roles-obligatoire:true}") boolean a2fRolesObligatoire) {
		this.permissions = permissions;
		this.roles = roles;
		this.emplacements = emplacements;
		this.a2fRolesObligatoire = a2fRolesObligatoire;
		this.utilisateurs = utilisateurs;
		this.encodeur = encodeur;
		this.journal = journal;
		this.emailAdmin = emailAdmin;
		this.motDePasseAdmin = motDePasseAdmin;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		synchroniserPermissions();
		creerRolesManquants();
		creerEmplacementTransit();
		amorcerAdministrateur();
	}

	private void synchroniserPermissions() {
		for (CatalogueDroits.DefinitionPermission d : CatalogueDroits.permissions()) {
			permissions.findByCode(d.code()).ifPresentOrElse(
					existante -> existante.setLibelle(d.libelle()),
					() -> permissions.save(new Permission(d.code(), d.libelle(), d.module(), d.action())));
		}
	}

	private void creerRolesManquants() {
		for (CatalogueDroits.DefinitionRole d : CatalogueDroits.rolesParDefaut()) {
			if (roles.existsByCode(d.code())) {
				continue;
			}
			Role role = new Role(d.code(), d.libelle(), d.description(), true);
			role.setPerimetreGlobal(d.perimetreGlobal());
			role.setDoubleAuthentificationRequise(a2fRolesObligatoire && d.doubleAuthentificationRequise());
			role.getPermissions().addAll(permissions.findByCodeIn(d.permissions()));
			roles.save(role);
			log.info("Rôle livré par défaut créé : {} ({} permissions)", d.code(), role.getPermissions().size());
		}
	}

	/** Emplacement virtuel des motos en transit interne (SRS §3.4) : créé et géré par le système. */
	private void creerEmplacementTransit() {
		if (!emplacements.codeUtilise("TRANSIT")) {
			emplacements.save(new Emplacement("TRANSIT", "Transit interne", TypeEmplacement.TRANSIT, true));
		}
	}

	private void amorcerAdministrateur() {
		if (utilisateurs.count() > 0) {
			return;
		}
		String motDePasse = motDePasseAdmin;
		boolean genere = motDePasse == null || motDePasse.isBlank();
		if (genere) {
			byte[] octets = new byte[18];
			new SecureRandom().nextBytes(octets);
			motDePasse = Base64.getUrlEncoder().withoutPadding().encodeToString(octets) + "!aA1";
		}

		Utilisateur admin = new Utilisateur("Administrateur", null, emailAdmin.trim().toLowerCase());
		admin.setMotDePasseHash(encodeur.encode(motDePasse));
		admin.setStatut(StatutUtilisateur.ACTIF);
		roles.findByCode("admin").ifPresent(admin.getRoles()::add);
		utilisateurs.save(admin);

		journal.enregistrer(EntreeAudit.de(CodeModule.ADM, "utilisateur.amorce", "Utilisateur", String.valueOf(admin.getId()))
				.avecValeurs(null, Map.of("email", admin.getEmail(), "roles", List.of("admin"))));

		if (genere) {
			log.warn("Premier administrateur créé : {} / mot de passe généré : {} (à changer sans délai)", admin.getEmail(), motDePasse);
		} else {
			log.warn("Premier administrateur créé : {}", admin.getEmail());
		}
	}

}
