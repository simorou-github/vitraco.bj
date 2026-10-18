package bj.vitraco.api.adm.droits;

import bj.vitraco.api.adm.utilisateurs.Utilisateur;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "roles")
public class Role {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private String code;

	@Column(nullable = false)
	private String libelle;

	private String description;

	@Column(nullable = false)
	private boolean systeme;

	/** Voit tous les emplacements, pas seulement ceux qui lui sont attribués (la Direction). */
	@Column(name = "perimetre_global", nullable = false)
	private boolean perimetreGlobal;

	/** Les utilisateurs de ce rôle doivent activer la double authentification (EF-ADM-10). */
	@Column(name = "double_authentification_requise", nullable = false)
	private boolean doubleAuthentificationRequise;

	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(name = "role_permission",
			joinColumns = @JoinColumn(name = "role_id"),
			inverseJoinColumns = @JoinColumn(name = "permission_id"))
	@BatchSize(size = 50)
	private Set<Permission> permissions = new HashSet<>();

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "cree_par")
	private Utilisateur creePar;

	@Column(name = "cree_le", nullable = false, updatable = false)
	private Instant creeLe;

	@Column(name = "modifie_le", nullable = false)
	private Instant modifieLe;

	protected Role() {
	}

	public Role(String code, String libelle, String description, boolean systeme) {
		this.code = code;
		this.libelle = libelle;
		this.description = description;
		this.systeme = systeme;
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

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public boolean isSysteme() {
		return systeme;
	}

	public boolean isPerimetreGlobal() {
		return perimetreGlobal;
	}

	public void setPerimetreGlobal(boolean perimetreGlobal) {
		this.perimetreGlobal = perimetreGlobal;
	}

	public boolean isDoubleAuthentificationRequise() {
		return doubleAuthentificationRequise;
	}

	public void setDoubleAuthentificationRequise(boolean doubleAuthentificationRequise) {
		this.doubleAuthentificationRequise = doubleAuthentificationRequise;
	}

	public Set<Permission> getPermissions() {
		return permissions;
	}

	public Utilisateur getCreePar() {
		return creePar;
	}

	public void setCreePar(Utilisateur creePar) {
		this.creePar = creePar;
	}

	public Instant getCreeLe() {
		return creeLe;
	}

}
