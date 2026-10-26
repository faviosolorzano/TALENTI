package com.talenti.github.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.talenti.github.client.AccessToken;
import com.talenti.github.client.GithubClient;
import com.talenti.github.client.GithubNotFoundException;
import com.talenti.github.client.GithubRateLimitedException;
import com.talenti.github.client.GithubUnavailableException;
import com.talenti.github.client.GithubUser;
import com.talenti.github.client.OAuthExchangeFailedException;
import com.talenti.github.client.RepoData;
import com.talenti.github.config.GithubProperties;
import com.talenti.github.domain.OAuthState;
import com.talenti.github.persistence.GithubStore;
import com.talenti.github.persistence.GithubStore.StateRow;
import com.talenti.shared.audit.AuditEvent;
import com.talenti.shared.audit.AuditRecorder;
import com.talenti.shared.web.ApiException;

/**
 * Inicio de sesión con GitHub (OAuth App, 013-24 a 013-30). El {@code state} se guarda solo como hash, ligado a la
 * persona y al hash de su sesión de Talenti, y se consume una sola vez antes de canjear el código. El token del canje
 * vive en una variable local de {@link #callback}: se usa para leer {@code /user} y la primera lista de repositorios
 * y se descarta; nunca llega a la base, a la sesión, a los registros ni a la respuesta (013-27).
 */
@Service
public class GithubOAuthService {

	private static final Logger log = LoggerFactory.getLogger(GithubOAuthService.class);

	/** Resultado de la vuelta (013-29). */
	public enum Outcome {

		LINKED(null), CANCELLED("cancelled"), INVALID_STATE("invalid-state"), OAUTH_FAILED("oauth-failed"),
		GITHUB_UNAVAILABLE("github-unavailable"), ACCOUNT_TAKEN("github-account-taken"),
		SESSION_EXPIRED("session-expired");

		private final String code;

		Outcome(String code) {
			this.code = code;
		}

		/** El código de error de la redirección; nulo si se vinculó. */
		public String code() {
			return code;
		}

	}

	private final GithubProperties properties;

	private final GithubStore store;

	private final GithubClient client;

	private final GithubService github;

	private final AuditRecorder audit;

	private final Clock clock;

	private final TransactionTemplate transaction;

	GithubOAuthService(GithubProperties properties, GithubStore store, GithubClient client, GithubService github,
			AuditRecorder audit, Clock clock, PlatformTransactionManager transactionManager) {
		this.properties = properties;
		this.store = store;
		this.client = client;
		this.github = github;
		this.audit = audit;
		this.clock = clock;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	public boolean enabled() {
		return properties.oauth().enabled();
	}

	/** 503 {@code github-oauth-disabled} si la conexión está apagada (013-24). */
	public void requireEnabled() {
		if (!enabled()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "github-oauth-disabled");
		}
	}

	/** 013-25: un {@code state} nuevo (invalida el anterior) y la dirección de autorización, sin {@code scope}. */
	public Views.AuthorizeUrl start(UUID userId, String sessionId) {
		requireEnabled();
		if (store.findLink(userId).isPresent()) {
			throw new ApiException(HttpStatus.CONFLICT, "already-linked");
		}
		String state = OAuthState.newState();
		Instant now = clock.instant();
		transaction.executeWithoutResult(status -> store.replaceState(userId, OAuthState.sha256(state),
				OAuthState.sha256(sessionId), now.plus(properties.oauth().stateTtl()), now));
		GithubProperties.OAuth oauth = properties.oauth();
		return new Views.AuthorizeUrl(
				OAuthState.authorizeUrl(oauth.baseUrl(), oauth.clientId(), oauth.redirectUri(), state));
	}

	/**
	 * 013-26 a 013-29: la vuelta desde GitHub. Nunca lanza: todo termina en un {@link Outcome}.
	 *
	 * @param userId    la persona de la sesión; nulo si no hay sesión válida
	 * @param student   si la sesión es de un estudiante
	 * @param sessionId id de la sesión de Talenti; nulo si no hay
	 */
	public Outcome callback(UUID userId, boolean student, String sessionId, String code, String state, String error) {
		if (userId == null || sessionId == null) {
			return Outcome.SESSION_EXPIRED;
		}
		if (state == null || state.isBlank() || state.length() > 200) {
			return Outcome.INVALID_STATE;
		}
		Instant now = clock.instant();
		// Borrado atómico ANTES de canjear: una segunda vuelta con el mismo state ya no lo encuentra (013-26).
		StateRow row = store.consumeState(OAuthState.sha256(state)).orElse(null);
		store.deleteExpiredStates(now);
		if (row == null || !student || !row.userId().equals(userId)
				|| !row.sessionRef().equals(OAuthState.sha256(sessionId)) || !now.isBefore(row.expiresAt())) {
			return Outcome.INVALID_STATE;
		}
		if (error != null && !error.isBlank()) {
			return "access_denied".equals(error) ? Outcome.CANCELLED : Outcome.OAUTH_FAILED;
		}
		if (code == null || code.isBlank() || code.length() > 200) {
			return Outcome.OAUTH_FAILED;
		}
		if (store.findLink(userId).isPresent()) {
			return Outcome.INVALID_STATE;
		}
		GithubUser githubUser;
		List<RepoData> repos = null;
		try {
			AccessToken token = client.exchangeCode(code);
			githubUser = client.user(token);
			try {
				repos = client.repositories(githubUser.login(), githubUser.id(), token, null).value();
			}
			catch (GithubUnavailableException | GithubRateLimitedException | GithubNotFoundException e) {
				// La cuenta ya quedó comprobada: los repositorios se leerán después por la API pública (013-07).
				log.info("GitHub: la primera lista de repositorios no se pudo leer al vincular");
			}
			// Fin del uso del token: sale de alcance aquí y no se guarda en ningún lado.
		}
		catch (OAuthExchangeFailedException e) {
			return Outcome.OAUTH_FAILED;
		}
		catch (GithubUnavailableException | GithubRateLimitedException | GithubNotFoundException e) {
			return Outcome.GITHUB_UNAVAILABLE;
		}
		if (!"User".equals(githubUser.type())) {
			return Outcome.OAUTH_FAILED;
		}
		List<RepoData> firstList = repos == null ? null : github.visible(repos);
		try {
			transaction.executeWithoutResult(status -> {
				store.insertLink(userId, githubUser.login(), githubUser.id(), now);
				if (firstList != null) {
					store.syncRepos(userId, firstList);
					// Sin ETag: el de una respuesta con token no sirve para la API pública.
					store.markReposFetched(userId, null, now);
				}
				audit.record(new AuditEvent("GITHUB_LINKED", userId, "github_link", userId,
						Map.of("githubUserId", githubUser.id(), "repositories", firstList == null ? 0 : firstList.size())));
			});
		}
		catch (DuplicateKeyException e) {
			// Índice único sobre github_user_id (013-28) o un vínculo propio creado a la vez: no se dice de quién.
			return store.findLink(userId).isPresent() ? Outcome.INVALID_STATE : Outcome.ACCOUNT_TAKEN;
		}
		catch (DataIntegrityViolationException e) {
			// La cuenta de Talenti ya no existe (se borró con la sesión aún abierta).
			return Outcome.SESSION_EXPIRED;
		}
		return Outcome.LINKED;
	}

	/** {@code return-url?github=linked} o {@code ?github=error&code=…}; nunca lleva el {@code code} ni el token. */
	public String redirectFor(Outcome outcome) {
		String base = properties.oauth().returnUrl();
		String separator = base.contains("?") ? "&" : "?";
		return outcome == Outcome.LINKED ? base + separator + "github=linked"
				: base + separator + "github=error&code=" + outcome.code();
	}

}
