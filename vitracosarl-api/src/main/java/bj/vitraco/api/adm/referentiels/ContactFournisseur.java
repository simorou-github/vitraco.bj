package bj.vitraco.api.adm.referentiels;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "contacts_fournisseur")
public class ContactFournisseur {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String nom;

	private String fonction;

	private String email;

	private String telephone;

	protected ContactFournisseur() {
	}

	public ContactFournisseur(String nom, String fonction, String email, String telephone) {
		this.nom = nom;
		this.fonction = fonction;
		this.email = email;
		this.telephone = telephone;
	}

	public String getNom() {
		return nom;
	}

	public String getFonction() {
		return fonction;
	}

	public String getEmail() {
		return email;
	}

	public String getTelephone() {
		return telephone;
	}

}
