package bj.vitraco.api.adm.referentiels;

import bj.vitraco.api.adm.utilisateurs.Utilisateur;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "fournisseurs")
public class Fournisseur {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "raison_sociale", nullable = false)
	private String raisonSociale;

	@Column(nullable = false)
	private String pays;

	@Column(nullable = false)
	private String devise;

	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
	@JoinColumn(name = "fournisseur_id", nullable = false)
	@BatchSize(size = 50)
	private List<ContactFournisseur> contacts = new ArrayList<>();

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

	protected Fournisseur() {
	}

	public Fournisseur(String raisonSociale, String pays, String devise) {
		this.raisonSociale = raisonSociale;
		this.pays = pays;
		this.devise = devise;
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

	public String getRaisonSociale() {
		return raisonSociale;
	}

	public void setRaisonSociale(String raisonSociale) {
		this.raisonSociale = raisonSociale;
	}

	public String getPays() {
		return pays;
	}

	public void setPays(String pays) {
		this.pays = pays;
	}

	public String getDevise() {
		return devise;
	}

	public void setDevise(String devise) {
		this.devise = devise;
	}

	public List<ContactFournisseur> getContacts() {
		return contacts;
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
