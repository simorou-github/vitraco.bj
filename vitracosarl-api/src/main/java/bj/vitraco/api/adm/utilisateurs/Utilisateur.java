package bj.vitraco.api.adm.utilisateurs;

import bj.vitraco.api.adm.droits.Role;
import bj.vitraco.api.adm.emplacements.Emplacement;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "utilisateurs")
public class Utilisateur {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String nom;

	private String prenom;

	@Column(nullable = false)
	private String email;

	private String telephone;

	private String adresse;

	@Column(name = "mot_de_passe_hash")
	private String motDePasseHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private StatutUtilisateur statut = StatutUtilisateur.EN_ATTENTE_ACTIVATION;

	@Column(name = "tentatives_echec", nullable = false)
	private int tentativesEchec;

	@Column(name = "verrouille_jusqua")
	private Instant verrouilleJusqua;

	@Column(name = "derniere_connexion")
	private Instant derniereConnexion;

	/** Secret TOTP chiffré ; il ne protège le compte qu'une fois {@code a2fActiveLe} renseigné. */
	@Column(name = "a2f_secret_chiffre")
	private String a2fSecretChiffre;

	@Column(name = "a2f_active_le")
	private Instant a2fActiveLe;

	@Column(name = "a2f_dernier_pas")
	private Long a2fDernierPas;

	@Version
	private long version;

	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(name = "utilisateur_role",
			joinColumns = @JoinColumn(name = "utilisateur_id"),
			inverseJoinColumns = @JoinColumn(name = "role_id"))
	@BatchSize(size = 50)
	private Set<Role> roles = new HashSet<>();

	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(name = "emplacement_utilisateur",
			joinColumns = @JoinColumn(name = "utilisateur_id"),
			inverseJoinColumns = @JoinColumn(name = "emplacement_id"))
	@BatchSize(size = 50)
	private Set<Emplacement> emplacements = new HashSet<>();

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "cree_par")
	private Utilisateur creePar;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "modifie_par")
	private Utilisateur modifiePar;

	@Column(name = "cree_le", nullable = false, updatable = false)
	private Instant creeLe;

	@Column(name = "modifie_le", nullable = false)
	private Instant modifieLe;

	protected Utilisateur() {
	}

	public Utilisateur(String nom, String prenom, String email) {
		this.nom = nom;
		this.prenom = prenom;
		this.email = email;
	}

	@PrePersist
	void avantCreation() {
		creeLe = Instant.now();
		modifieLe = creeLe;
	}

	@PreUpdate
	void avantModification() {
		modifieLe = Instant.now();
	}

	public boolean estVerrouille(Instant maintenant) {
		return verrouilleJusqua != null && verrouilleJusqua.isAfter(maintenant);
	}

	public Long getId() {
		return id;
	}

	public String getNom() {
		return nom;
	}

	public void setNom(String nom) {
		this.nom = nom;
	}

	public String getPrenom() {
		return prenom;
	}

	public void setPrenom(String prenom) {
		this.prenom = prenom;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getTelephone() {
		return telephone;
	}

	public void setTelephone(String telephone) {
		this.telephone = telephone;
	}

	public String getAdresse() {
		return adresse;
	}

	public void setAdresse(String adresse) {
		this.adresse = adresse;
	}

	public String getMotDePasseHash() {
		return motDePasseHash;
	}

	public void setMotDePasseHash(String motDePasseHash) {
		this.motDePasseHash = motDePasseHash;
	}

	public StatutUtilisateur getStatut() {
		return statut;
	}

	public void setStatut(StatutUtilisateur statut) {
		this.statut = statut;
	}

	public int getTentativesEchec() {
		return tentativesEchec;
	}

	public void setTentativesEchec(int tentativesEchec) {
		this.tentativesEchec = tentativesEchec;
	}

	public Instant getVerrouilleJusqua() {
		return verrouilleJusqua;
	}

	public void setVerrouilleJusqua(Instant verrouilleJusqua) {
		this.verrouilleJusqua = verrouilleJusqua;
	}

	public Instant getDerniereConnexion() {
		return derniereConnexion;
	}

	public void setDerniereConnexion(Instant derniereConnexion) {
		this.derniereConnexion = derniereConnexion;
	}

	public Set<Role> getRoles() {
		return roles;
	}

	public String getA2fSecretChiffre() {
		return a2fSecretChiffre;
	}

	public void setA2fSecretChiffre(String a2fSecretChiffre) {
		this.a2fSecretChiffre = a2fSecretChiffre;
	}

	public Instant getA2fActiveLe() {
		return a2fActiveLe;
	}

	public void setA2fActiveLe(Instant a2fActiveLe) {
		this.a2fActiveLe = a2fActiveLe;
	}

	public long getA2fDernierPas() {
		return a2fDernierPas == null ? -1 : a2fDernierPas;
	}

	public void setA2fDernierPas(Long a2fDernierPas) {
		this.a2fDernierPas = a2fDernierPas;
	}

	public boolean a2fActive() {
		return a2fActiveLe != null;
	}

	/** Un de ses rôles exige le second facteur. */
	public boolean a2fRequise() {
		return roles.stream().anyMatch(Role::isDoubleAuthentificationRequise);
	}

	public Set<Emplacement> getEmplacements() {
		return emplacements;
	}

	/** Un de ses rôles donne la vue consolidée sur tous les emplacements. */
	public boolean aPerimetreGlobal() {
		return roles.stream().anyMatch(Role::isPerimetreGlobal);
	}

	public Utilisateur getCreePar() {
		return creePar;
	}

	public void setCreePar(Utilisateur creePar) {
		this.creePar = creePar;
	}

	public void setModifiePar(Utilisateur modifiePar) {
		this.modifiePar = modifiePar;
	}

	public Instant getCreeLe() {
		return creeLe;
	}

}
