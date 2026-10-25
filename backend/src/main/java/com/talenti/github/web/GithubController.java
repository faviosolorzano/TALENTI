package com.talenti.github.web;

import java.net.URI;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.talenti.github.service.GithubOAuthService;
import com.talenti.github.service.GithubOAuthService.Outcome;
import com.talenti.github.service.GithubService;
import com.talenti.github.service.Views;
import com.talenti.identity.CurrentUser;
import com.talenti.identity.Role;
import com.talenti.profile.ProfileView;

import tools.jackson.databind.JsonNode;

/**
 * GitHub del estudiante (spec 013, sección 6). El id de la persona sale siempre de la sesión: ninguna ruta recibe el
 * id de un usuario. Rol, {@code no-store} y límite los impone {@link StudentOnlyInterceptor} (salvo en la vuelta desde
 * GitHub, que se protege aquí y siempre redirige, 013-29).
 */
@RestController
@RequestMapping(GithubWebConfig.GITHUB)
class GithubController {

	private static final Logger log = LoggerFactory.getLogger(GithubController.class);

	private final GithubService github;

	private final GithubOAuthService oauth;

	GithubController(GithubService github, GithubOAuthService oauth) {
		this.github = github;
		this.oauth = oauth;
	}

	record SelectionRequest(List<String> repoIds) {

		@Override
		public String toString() {
			return "SelectionRequest[...]";
		}

	}

	record DecisionItem(String slug, String decision, JsonNode level) {

		@Override
		public String toString() {
			return "DecisionItem[...]";
		}

	}

	record DecisionsRequest(List<DecisionItem> items) {

		@Override
		public String toString() {
			return "DecisionsRequest[...]";
		}

	}

	@GetMapping
	ResponseEntity<Views.Link> link(@AuthenticationPrincipal CurrentUser user) {
		return github.link(user.id()).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
	}

	@PostMapping("/oauth/start")
	Views.AuthorizeUrl start(@AuthenticationPrincipal CurrentUser user, HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		return oauth.start(user.id(), session == null ? "" : session.getId());
	}

	/**
	 * La llama GitHub por el navegador. Siempre 302 a {@code return-url} (013-29), sin el {@code code} ni el token;
	 * nunca 500. Con la conexión apagada responde 503 {@code github-oauth-disabled} (013-24).
	 */
	@GetMapping("/oauth/callback")
	ResponseEntity<Void> callback(@AuthenticationPrincipal CurrentUser user, HttpServletRequest request,
			@RequestParam(required = false) String code, @RequestParam(required = false) String state,
			@RequestParam(required = false) String error) {
		oauth.requireEnabled();
		Outcome outcome;
		try {
			HttpSession session = request.getSession(false);
			outcome = oauth.callback(user == null ? null : user.id(), user != null && user.role() == Role.STUDENT,
					session == null ? null : session.getId(), code, state, error);
		}
		catch (RuntimeException unexpected) {
			// Solo la clase: el mensaje podría traer datos. La persona vuelve con un error claro, nunca un 500.
			log.warn("Vuelta de GitHub sin completar: {}", unexpected.getClass().getName());
			outcome = Outcome.OAUTH_FAILED;
		}
		log.info("Vuelta de GitHub: {}", outcome);
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(oauth.redirectFor(outcome)))
				.header(HttpHeaders.CACHE_CONTROL, "no-store").header("Referrer-Policy", "no-referrer").build();
	}

	@GetMapping("/repositories")
	Views.Repositories repositories(@AuthenticationPrincipal CurrentUser user) {
		return github.repositories(user.id());
	}

	@PutMapping("/repositories/selection")
	Views.Selection select(@AuthenticationPrincipal CurrentUser user,
			@RequestBody(required = false) SelectionRequest body) {
		return github.select(user.id(), body == null ? null : body.repoIds());
	}

	@PostMapping("/refresh")
	Views.Refresh refresh(@AuthenticationPrincipal CurrentUser user) {
		return github.refresh(user.id());
	}

	@GetMapping("/suggestions")
	Views.Suggestions suggestions(@AuthenticationPrincipal CurrentUser user) {
		return github.suggestions(user.id());
	}

	@PostMapping("/suggestions/decisions")
	ProfileView decide(@AuthenticationPrincipal CurrentUser user, @RequestBody(required = false) DecisionsRequest body) {
		List<Views.Decision> items = body == null || body.items() == null ? null
				: body.items().stream().map(GithubController::decision).toList();
		return github.decide(user.id(), items);
	}

	@DeleteMapping
	ResponseEntity<Void> unlink(@AuthenticationPrincipal CurrentUser user) {
		github.unlink(user.id());
		return ResponseEntity.noContent().build();
	}

	private static Views.Decision decision(DecisionItem item) {
		if (item == null) {
			return null;
		}
		JsonNode level = item.level();
		if (level == null || level.isNull() || level.isMissingNode()) {
			return new Views.Decision(item.slug(), item.decision(), null, false);
		}
		if (level.isIntegralNumber() && level.canConvertToInt()) {
			return new Views.Decision(item.slug(), item.decision(), level.intValue(), false);
		}
		return new Views.Decision(item.slug(), item.decision(), null, true);
	}

}
