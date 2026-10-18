package bj.vitraco.api.cat;

import bj.vitraco.api.adm.utilisateurs.Utilisateur;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** Un modèle de moto du catalogue. Les prix ne sont pas ici : ils vivent dans l'historique des prix. */
@Entity
@Table(name = "modeles")
public class Modele {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String marque;

	@Column(nullable = false)
	private String nom;

	@Column(name = "version_modele")
	private String versionModele;

	@Column(name = "code_article", nullable = false, updatable = false)
	private String codeArticle;

	private String description;

	@Column(name = "groupe_taxation")
	private String groupeTaxation;

	@Column(name = "format_chassis")
	private String formatChassis;

	@Column(nullable = false)
	private boolean actif = true;

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

	protected Modele() {
	}

	public Modele(String marque, String nom, String codeArticle) {
		this.marque = marque;
		this.nom = nom;
		this.codeArticle = codeArticle;
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

	public String getMarque() {
		return marque;
	}

	public void setMarque(String marque) {
		this.marque = marque;
	}

	public String getNom() {
		return nom;
	}

	public void setNom(String nom) {
		this.nom = nom;
	}

	public String getVersionModele() {
		return versionModele;
	}

	public void setVersionModele(String versionModele) {
		this.versionModele = versionModele;
	}

	public String getCodeArticle() {
		return codeArticle;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getGroupeTaxation() {
		return groupeTaxation;
	}

	public void setGroupeTaxation(String groupeTaxation) {
		this.groupeTaxation = groupeTaxation;
	}

	public String getFormatChassis() {
		return formatChassis;
	}

	public void setFormatChassis(String formatChassis) {
		this.formatChassis = formatChassis;
	}

	public boolean isActif() {
		return actif;
	}

	public void setActif(boolean actif) {
		this.actif = actif;
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
