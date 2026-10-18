package bj.vitraco.api.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Paramètres de pagination communs : {@code page} (à partir de 1) et {@code per_page} (100 au plus). */
public final class Pagination {

	private static final int PAR_PAGE_PAR_DEFAUT = 15;
	private static final int PAR_PAGE_MAX = 100;

	private Pagination() {
	}

	public static Pageable de(Integer page, Integer parPage, Sort tri) {
		int numero = page == null || page < 1 ? 1 : page;
		int taille = parPage == null || parPage < 1 ? PAR_PAGE_PAR_DEFAUT : Math.min(parPage, PAR_PAGE_MAX);
		return PageRequest.of(numero - 1, taille, tri);
	}

}
