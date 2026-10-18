package bj.vitraco.api.notif;

import bj.vitraco.api.common.ErreurMetierException;
import bj.vitraco.api.common.Pagination;
import bj.vitraco.api.common.ReponseApi;
import bj.vitraco.api.common.ReponsePaginee;
import bj.vitraco.api.common.security.ActeurAuthentifie;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Les notifications sont personnelles : aucune permission particulière, seulement être connecté. */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

	private final NotificationLecture lecture;

	NotificationController(NotificationLecture lecture) {
		this.lecture = lecture;
	}

	@GetMapping
	ReponsePaginee<NotificationDto> lister(@RequestParam(name = "non_lues", required = false) Boolean nonLues,
										   @RequestParam(required = false) Integer page,
										   @RequestParam(name = "per_page", required = false) Integer parPage) {
		return ReponsePaginee.de(lecture.lister(moi(), Boolean.TRUE.equals(nonLues), Pagination.de(page, parPage, Sort.unsorted())), n -> n);
	}

	@GetMapping("/non-lues/nombre")
	ReponseApi<Map<String, Long>> nombreNonLues() {
		return ReponseApi.de(Map.of("nombre", lecture.nonLues(moi())));
	}

	@PostMapping("/{id}/lire")
	ResponseEntity<Void> lire(@PathVariable Long id) {
		lecture.marquerLue(moi(), id);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/lire-tout")
	ResponseEntity<Void> lireTout() {
		lecture.toutMarquerLu(moi());
		return ResponseEntity.noContent().build();
	}

	private static Long moi() {
		return ActeurAuthentifie.courant().map(ActeurAuthentifie::id)
				.orElseThrow(() -> new ErreurMetierException("NON_AUTHENTIFIE", "Connexion requise.", HttpStatus.UNAUTHORIZED));
	}

}
