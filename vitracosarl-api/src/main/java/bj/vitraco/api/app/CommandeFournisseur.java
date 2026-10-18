package bj.vitraco.api.app;

import bj.vitraco.api.adm.referentiels.Fournisseur;
import bj.vitraco.api.adm.utilisateurs.Utilisateur;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Une commande passée à un fournisseur. Elle n'agit jamais sur le stock (RG-06). */
@Entity
@Table(name = "commandes_fournisseur")
public class CommandeFournisseur {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, updatable = false)
	private String numero;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "fournisseur_id")
	private Fournisseur fournisseur;

	@Column(name = "date_commande", nullable = false)
	private LocalDate dateCommande;

	@Column(nullable = false)
	private String devise;

	@Column(name = "taux_change", nullable = false)
	private BigDecimal tauxChange;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Incoterm incoterm;

	@Enumerated(EnumType.STRING)
	@Column(name = "base_prix", nullable = false)
	private BasePrix basePrix;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private StatutCommande statut = StatutCommande.COMMANDEE;

	@Column(name = "reference_proforma")
	private String referenceProforma;

	private String observation;

	@OneToMany(mappedBy = "commande", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
	@BatchSize(size = 50)
	private List<LigneCommande> lignes = new ArrayList<>();

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

	protected CommandeFournisseur() {
	}

	CommandeFournisseur(String numero, Fournisseur fournisseur) {
		this.numero = numero;
		this.fournisseur = fournisseur;
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

	public Fournisseur getFournisseur() {
		return fournisseur;
	}

	void setFournisseur(Fournisseur fournisseur) {
		this.fournisseur = fournisseur;
	}

	public LocalDate getDateCommande() {
		return dateCommande;
	}

	void setDateCommande(LocalDate dateCommande) {
		this.dateCommande = dateCommande;
	}

	public String getDevise() {
		return devise;
	}

	void setDevise(String devise) {
		this.devise = devise;
	}

	public BigDecimal getTauxChange() {
		return tauxChange;
	}

	void setTauxChange(BigDecimal tauxChange) {
		this.tauxChange = tauxChange;
	}

	public Incoterm getIncoterm() {
		return incoterm;
	}

	void setIncoterm(Incoterm incoterm) {
		this.incoterm = incoterm;
	}

	public BasePrix getBasePrix() {
		return basePrix;
	}

	void setBasePrix(BasePrix basePrix) {
		this.basePrix = basePrix;
	}

	public StatutCommande getStatut() {
		return statut;
	}

	void setStatut(StatutCommande statut) {
		this.statut = statut;
	}

	public String getReferenceProforma() {
		return referenceProforma;
	}

	void setReferenceProforma(String referenceProforma) {
		this.referenceProforma = referenceProforma;
	}

	public String getObservation() {
		return observation;
	}

	void setObservation(String observation) {
		this.observation = observation;
	}

	public List<LigneCommande> getLignes() {
		return lignes;
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
