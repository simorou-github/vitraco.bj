package bj.vitraco.api.common;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** Liste paginée : {@code { "data": [...], "meta": { current_page, last_page, per_page, total } }}. */
public record ReponsePaginee<T>(List<T> data, Meta meta) {

	public record Meta(int currentPage, int lastPage, int perPage, long total) {
	}

	public static <E, T> ReponsePaginee<T> de(Page<E> page, Function<E, T> transformation) {
		return new ReponsePaginee<>(
				page.getContent().stream().map(transformation).toList(),
				new Meta(page.getNumber() + 1, Math.max(page.getTotalPages(), 1), page.getSize(), page.getTotalElements()));
	}

}
