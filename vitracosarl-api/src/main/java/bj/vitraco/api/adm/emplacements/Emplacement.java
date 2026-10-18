package bj.vitraco.api.adm.emplacements;

import bj.vitraco.api.adm.utilisateurs.Utilisateur;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "emplacements")
public class Emplacement {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, updatable = false)
	private String code;

	@Column(nullable = false)
	private String nom;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private TypeEmplacement type;

	@Enumerated(EnumType.STRING)
	@Column(name = "mode_boutique")
	private ModeBoutique modeBoutique;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "magasin_rattachement_id")
	private Emplacement magasinRattachement;

	private String adresse;

	@Column(name = "responsable_nom")
	private String responsableNom;

	private String email;

	private String telephone;

	@Column(nullable = false)
	private boolean actif = true;

	@Column(nullable = false, updatable = false)
	private boolean systeme;

	@Version
	private long version;

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

	protected Emplacement() {
	}

	public Emplacement(String code, String nom, TypeEmplacement type, boolean systeme) {
		this.code = code;
		this.nom = nom;
		this.type = type;
		this.systeme = systeme;
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

	public Long getId() {
		return id;
	}

	public String getCode() {
		return code;
	}

	public String getNom() {
		return nom;
	}

	public void setNom(String nom) {
		this.nom = nom;
	}

	public TypeEmplacement getType() {
		return type;
	}

	public ModeBoutique getModeBoutique() {
		return modeBoutique;
	}

	public void setModeBoutique(ModeBoutique modeBoutique) {
		this.modeBoutique = modeBoutique;
	}

	public Emplacement getMagasinRattachement() {
		return magasinRattachement;
	}

	public void setMagasinRattachement(Emplacement magasinRattachement) {
		this.magasinRattachement = magasinRattachement;
	}

	public String getAdresse() {
		return adresse;
	}

	public void setAdresse(String adresse) {
		this.adresse = adresse;
	}

	public String getResponsableNom() {
		return responsableNom;
	}

	public void setResponsableNom(String responsableNom) {
		this.responsableNom = responsableNom;
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

	public boolean isActif() {
		return actif;
	}

	public void setActif(boolean actif) {
		this.actif = actif;
	}

	public boolean isSysteme() {
		return systeme;
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
