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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "frais_arrivage")
public class FraisArrivage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "arrivage_id", updatable = false)
	private Arrivage arrivage;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private TypeFrais type;

	@Column(nullable = false)
	private String libelle;

	@Column(nullable = false)
	private BigDecimal montant;

	@Column(nullable = false)
	private String devise;

	@Column(name = "taux_change", nullable = false)
	private BigDecimal tauxChange;

	@Column(name = "montant_fcfa", nullable = false)
	private long montantFcfa;

	@Column(name = "reference_piece")
	private String referencePiece;

	@Column(name = "date_frais", nullable = false)
	private LocalDate dateFrais;

	@Column(nullable = false)
	private boolean annule;

	@Column(name = "motif_annulation")
	private String motifAnnulation;

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

	protected FraisArrivage() {
	}

	FraisArrivage(Arrivage arrivage) {
		this.arrivage = arrivage;
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

	public Arrivage getArrivage() {
		return arrivage;
	}

	public TypeFrais getType() {
		return type;
	}

	public String getLibelle() {
		return libelle;
	}

	public BigDecimal getMontant() {
		return montant;
	}

	public String getDevise() {
		return devise;
	}

	public BigDecimal getTauxChange() {
		return tauxChange;
	}

	public long getMontantFcfa() {
		return montantFcfa;
	}

	public String getReferencePiece() {
		return referencePiece;
	}

	public LocalDate getDateFrais() {
		return dateFrais;
	}

	public boolean isAnnule() {
		return annule;
	}

	public String getMotifAnnulation() {
		return motifAnnulation;
	}

	void renseigner(TypeFrais type, String libelle, BigDecimal montant, String devise, BigDecimal tauxChange, long montantFcfa,
					String referencePiece, LocalDate dateFrais) {
		this.type = type;
		this.libelle = libelle;
		this.montant = montant;
		this.devise = devise;
		this.tauxChange = tauxChange;
		this.montantFcfa = montantFcfa;
		this.referencePiece = referencePiece;
		this.dateFrais = dateFrais;
	}

	void annuler(String motif) {
		this.annule = true;
		this.motifAnnulation = motif;
	}

	void setCreePar(Utilisateur creePar) {
		this.creePar = creePar;
	}

	void setModifiePar(Utilisateur modifiePar) {
		this.modifiePar = modifiePar;
	}

}
