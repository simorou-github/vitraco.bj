package bj.vitraco.api.adm.auth;

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
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "jetons_compte")
public class JetonCompte {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "utilisateur_id")
	private Utilisateur utilisateur;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private TypeJeton type;

	@Column(nullable = false, unique = true)
	private String empreinte;

	@Column(name = "expire_le", nullable = false)
	private Instant expireLe;

	@Column(name = "utilise_le")
	private Instant utiliseLe;

	@Column(name = "cree_le", nullable = false, updatable = false)
	private Instant creeLe = Instant.now();

	protected JetonCompte() {
	}

	public JetonCompte(Utilisateur utilisateur, TypeJeton type, String empreinte, Instant expireLe) {
		this.utilisateur = utilisateur;
		this.type = type;
		this.empreinte = empreinte;
		this.expireLe = expireLe;
	}

	public boolean estValide(Instant maintenant) {
		return utiliseLe == null && expireLe.isAfter(maintenant);
	}

	public void marquerUtilise(Instant maintenant) {
		this.utiliseLe = maintenant;
	}

	public Utilisateur getUtilisateur() {
		return utilisateur;
	}

	public TypeJeton getType() {
		return type;
	}

	public Instant getUtiliseLe() {
		return utiliseLe;
	}

}
