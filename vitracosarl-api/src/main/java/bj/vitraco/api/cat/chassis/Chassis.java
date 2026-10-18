package bj.vitraco.api.cat.chassis;

import bj.vitraco.api.adm.emplacements.Emplacement;
import bj.vitraco.api.adm.utilisateurs.Utilisateur;
import bj.vitraco.api.cat.Modele;
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
import java.time.LocalDate;

/**
 * Une moto physique, identifiée par un numéro de châssis unique (RG-01). Le statut et l'emplacement ne se
 * modifient que par {@link ChassisService}, dans la transaction qui écrit le mouvement correspondant.
 */
@Entity
@Table(name = "chassis")
public class Chassis {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String numero;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "modele_id")
	private Modele modele;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private StatutChassis statut;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "emplacement_id")
	private Emplacement emplacement;

	@Column(name = "cout_revient")
	private Long coutRevient;

	@Column(name = "arrivage_id")
	private Long arrivageId;

	@Column(name = "reception_id")
	private Long receptionId;

	@Column(name = "date_entree")
	private LocalDate dateEntree;

	@Column(name = "numero_moteur")
	private String numeroMoteur;

	private String couleur;

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

	protected Chassis() {
	}

	Chassis(String numero, Modele modele, StatutChassis statut, Emplacement emplacement) {
		this.numero = numero;
		this.modele = modele;
		this.statut = statut;
		this.emplacement = emplacement;
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

	public String getNumero() {
		return numero;
	}

	void setNumero(String numero) {
		this.numero = numero;
	}

	public Modele getModele() {
		return modele;
	}

	public StatutChassis getStatut() {
		return statut;
	}

	void setStatut(StatutChassis statut) {
		this.statut = statut;
	}

	void setEmplacement(Emplacement emplacement) {
		this.emplacement = emplacement;
	}

	public Emplacement getEmplacement() {
		return emplacement;
	}

	public Long getCoutRevient() {
		return coutRevient;
	}

	void setCoutRevient(Long coutRevient) {
		this.coutRevient = coutRevient;
	}

	public Long getArrivageId() {
		return arrivageId;
	}

	void setArrivageId(Long arrivageId) {
		this.arrivageId = arrivageId;
	}

	public Long getReceptionId() {
		return receptionId;
	}

	void setReceptionId(Long receptionId) {
		this.receptionId = receptionId;
	}

	public LocalDate getDateEntree() {
		return dateEntree;
	}

	void setDateEntree(LocalDate dateEntree) {
		this.dateEntree = dateEntree;
	}

	public String getNumeroMoteur() {
		return numeroMoteur;
	}

	void setNumeroMoteur(String numeroMoteur) {
		this.numeroMoteur = numeroMoteur;
	}

	public String getCouleur() {
		return couleur;
	}

	void setCouleur(String couleur) {
		this.couleur = couleur;
	}

	void setCreePar(Utilisateur creePar) {
		this.creePar = creePar;
	}

	void setModifiePar(Utilisateur modifiePar) {
		this.modifiePar = modifiePar;
	}

	public Instant getCreeLe() {
		return creeLe;
	}

}
