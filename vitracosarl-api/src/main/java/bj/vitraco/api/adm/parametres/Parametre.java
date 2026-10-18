package bj.vitraco.api.adm.parametres;

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
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/** Une version d'un paramètre : sa valeur vaut à partir de {@code dateEffet}. */
@Entity
@Table(name = "parametres")
public class Parametre {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, updatable = false)
	private String cle;

	@Column(nullable = false)
	private String valeur;

	@Column(name = "date_effet", nullable = false, updatable = false)
	private LocalDate dateEffet;

	@Column(name = "cree_le", nullable = false, updatable = false)
	private Instant creeLe;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "cree_par")
	private Utilisateur creePar;

	protected Parametre() {
	}

	public Parametre(String cle, String valeur, LocalDate dateEffet, Utilisateur creePar) {
		this.cle = cle;
		this.valeur = valeur;
		this.dateEffet = dateEffet;
		this.creePar = creePar;
	}

	@PrePersist
	void avantCreation() {
		creeLe = Instant.now();
	}

	public String getCle() {
		return cle;
	}

	public String getValeur() {
		return valeur;
	}

	public void setValeur(String valeur) {
		this.valeur = valeur;
	}

	public LocalDate getDateEffet() {
		return dateEffet;
	}

	public Instant getCreeLe() {
		return creeLe;
	}

	public Utilisateur getCreePar() {
		return creePar;
	}

}
