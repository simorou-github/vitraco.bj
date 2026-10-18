package bj.vitraco.api.adm.referentiels;

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
@Table(name = "modes_paiement")
public class ModePaiement {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, updatable = false)
	private String code;

	@Column(nullable = false)
	private String libelle;

	@Enumerated(EnumType.STRING)
	@Column(name = "type_dgi", nullable = false)
	private TypeDgi typeDgi;

	private String justificatif;

	@Column(name = "justificatif_obligatoire", nullable = false)
	private boolean justificatifObligatoire = true;

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

	protected ModePaiement() {
	}

	public ModePaiement(String code, String libelle, TypeDgi typeDgi) {
		this.code = code;
		this.libelle = libelle;
		this.typeDgi = typeDgi;
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

	public String getLibelle() {
		return libelle;
	}

	public void setLibelle(String libelle) {
		this.libelle = libelle;
	}

	public TypeDgi getTypeDgi() {
		return typeDgi;
	}

	public void setTypeDgi(TypeDgi typeDgi) {
		this.typeDgi = typeDgi;
	}

	public String getJustificatif() {
		return justificatif;
	}

	public void setJustificatif(String justificatif) {
		this.justificatif = justificatif;
	}

	public boolean isJustificatifObligatoire() {
		return justificatifObligatoire;
	}

	public void setJustificatifObligatoire(boolean justificatifObligatoire) {
		this.justificatifObligatoire = justificatifObligatoire;
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
