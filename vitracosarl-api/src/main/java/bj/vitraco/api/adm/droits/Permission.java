package bj.vitraco.api.adm.droits;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "permissions")
public class Permission {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private String code;

	@Column(nullable = false)
	private String libelle;

	@Column(nullable = false)
	private String module;

	@Column(nullable = false)
	private String action;

	protected Permission() {
	}

	public Permission(String code, String libelle, String module, String action) {
		this.code = code;
		this.libelle = libelle;
		this.module = module;
		this.action = action;
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

	public String getModule() {
		return module;
	}

	public String getAction() {
		return action;
	}

}
