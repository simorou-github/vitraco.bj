package bj.vitraco.api.app;

import bj.vitraco.api.cat.Modele;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "lignes_commande_fournisseur")
public class LigneCommande {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "commande_id")
	private CommandeFournisseur commande;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "modele_id")
	private Modele modele;

	@Column(nullable = false)
	private int quantite;

	@Column(name = "prix_unitaire", nullable = false)
	private BigDecimal prixUnitaire;

	@Column(name = "quantite_recue", nullable = false)
	private int quantiteRecue;

	protected LigneCommande() {
	}

	LigneCommande(CommandeFournisseur commande, Modele modele, int quantite, BigDecimal prixUnitaire) {
		this.commande = commande;
		this.modele = modele;
		this.quantite = quantite;
		this.prixUnitaire = prixUnitaire;
	}

	public Long getId() {
		return id;
	}

	public Modele getModele() {
		return modele;
	}

	public int getQuantite() {
		return quantite;
	}

	public BigDecimal getPrixUnitaire() {
		return prixUnitaire;
	}

	public int getQuantiteRecue() {
		return quantiteRecue;
	}

	void modifier(int quantite, BigDecimal prixUnitaire) {
		this.quantite = quantite;
		this.prixUnitaire = prixUnitaire;
	}

	void ajouterRecue(int quantite) {
		this.quantiteRecue += quantite;
	}

}
