package bj.vitraco.api.app;

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
import java.time.LocalDate;

/** Un envoi d'une commande : B/L, conteneur, dates prévues et réelles, port, dédouanement. Une commande peut en avoir plusieurs. */
@Entity
@Table(name = "arrivages")
public class Arrivage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "commande_id", updatable = false)
	private CommandeFournisseur commande;

	@Column(name = "numero_bl", nullable = false)
	private String numeroBl;

	private String conteneur;

	private String port;

	@Column(name = "date_depart_prevue")
	private LocalDate dateDepartPrevue;

	@Column(name = "date_depart_reelle")
	private LocalDate dateDepartReelle;

	@Column(name = "date_arrivee_prevue")
	private LocalDate dateArriveePrevue;

	@Column(name = "date_arrivee_reelle")
	private LocalDate dateArriveeReelle;

	@Enumerated(EnumType.STRING)
	@Column(name = "statut_douane", nullable = false)
	private StatutDouane statutDouane = StatutDouane.NON_DEMARREE;

	private String observation;

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

	protected Arrivage() {
	}

	Arrivage(CommandeFournisseur commande) {
		this.commande = commande;
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

	public CommandeFournisseur getCommande() {
		return commande;
	}

	public String getNumeroBl() {
		return numeroBl;
	}

	void setNumeroBl(String numeroBl) {
		this.numeroBl = numeroBl;
	}

	public String getConteneur() {
		return conteneur;
	}

	void setConteneur(String conteneur) {
		this.conteneur = conteneur;
	}

	public String getPort() {
		return port;
	}

	void setPort(String port) {
		this.port = port;
	}

	public LocalDate getDateDepartPrevue() {
		return dateDepartPrevue;
	}

	void setDateDepartPrevue(LocalDate d) {
		this.dateDepartPrevue = d;
	}

	public LocalDate getDateDepartReelle() {
		return dateDepartReelle;
	}

	void setDateDepartReelle(LocalDate d) {
		this.dateDepartReelle = d;
	}

	public LocalDate getDateArriveePrevue() {
		return dateArriveePrevue;
	}

	void setDateArriveePrevue(LocalDate d) {
		this.dateArriveePrevue = d;
	}

	public LocalDate getDateArriveeReelle() {
		return dateArriveeReelle;
	}

	void setDateArriveeReelle(LocalDate d) {
		this.dateArriveeReelle = d;
	}

	public StatutDouane getStatutDouane() {
		return statutDouane;
	}

	void setStatutDouane(StatutDouane statutDouane) {
		this.statutDouane = statutDouane;
	}

	public String getObservation() {
		return observation;
	}

	void setObservation(String observation) {
		this.observation = observation;
	}

	public Utilisateur getCreePar() {
		return creePar;
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
