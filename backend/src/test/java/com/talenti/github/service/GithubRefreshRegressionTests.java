package com.talenti.github.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.talenti.github.client.*;
import com.talenti.github.config.GithubProperties;
import com.talenti.github.persistence.GithubStore;
import com.talenti.github.persistence.GithubStore.LinkRow;
import com.talenti.github.persistence.GithubStore.RepoRow;
import com.talenti.profile.SkillEvidenceWriter;
import com.talenti.shared.audit.AuditRecorder;
import com.talenti.vacancy.SkillCatalog;

class GithubRefreshRegressionTests {

	private final GithubStore store = mock(GithubStore.class);
	private final GithubClient client = mock(GithubClient.class);
	private final UUID user = UUID.randomUUID(), localRepoId = UUID.randomUUID();
	private final Instant now = Instant.parse("2026-10-26T15:00:00Z");
	private GithubService service;
	private LinkRow link;

	@BeforeEach
	void setup() {
		var config = new GithubProperties("http://127.0.0.1", "2022-11-28", "", null, false, 1, 6, 10,
				Duration.ofHours(24), Duration.ofMinutes(10), 5, Duration.ofSeconds(1), Duration.ofSeconds(1),
				30, ZoneId.of("America/Lima"), "");
		var tx = mock(PlatformTransactionManager.class);
		when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		service = new GithubService(config, store, client, mock(SkillCatalog.class), mock(SkillEvidenceWriter.class),
				null, mock(AuditRecorder.class), Clock.fixed(now, ZoneOffset.UTC), tx);
		link = new LinkRow(user, "old-name", 123, "VERIFIED", now.minusSeconds(90000), "old-etag",
				now.minusSeconds(90000), List.of());
		when(store.findLink(user)).thenReturn(Optional.of(link));
		when(store.findRepos(user)).thenReturn(List.of());
		when(client.userById(123)).thenReturn(new GithubUser(123, "new-name", "User"));
		when(client.repositories("new-name", 123, null, null)).thenReturn(new Fetched<>(List.of(), "new-etag", false));
	}

	@Test
	void t013_05_expiredCacheResolvesNumericIdentityAndDropsOldEtag() {
		assertThat(service.repositories(user).stale()).isFalse();
		var order = inOrder(client, store);
		order.verify(client).userById(123);
		order.verify(client).repositories("new-name", 123, null, null);
		order.verify(store).updateLogin(user, "new-name");
		order.verify(store).syncRepos(user, List.of());
		verify(client, never()).repositories(eq("old-name"), anyLong(), any(), any());
	}

	@Test
	void t013_05_unavailableIdentityKeepsStaleCacheWithoutUsingReusedName() {
		when(client.userById(123)).thenThrow(new GithubNotFoundException());
		assertThat(service.repositories(user).stale()).isTrue();
		verify(client, never()).repositories(anyString(), anyLong(), any(), any());
		verify(store, never()).syncRepos(any(), any());
		verify(store, never()).updateLogin(any(), any());
	}

	@Test
	void t013_05_unexpectedNumericIdentityCannotOverwriteCache() {
		when(client.userById(123)).thenReturn(new GithubUser(456, "old-name", "User"));
		assertThat(service.repositories(user).stale()).isTrue();
		verify(client, never()).repositories(anyString(), anyLong(), any(), any());
	}

	@Test
	void t013_20_noCacheAndUnavailableIdentityReturnsUnavailable() {
		when(store.findLink(user)).thenReturn(Optional.of(new LinkRow(user, "old-name", 123, "VERIFIED", now,
				null, null, List.of())));
		when(client.userById(123)).thenThrow(new GithubUnavailableException("simulated"));
		assertThatThrownBy(() -> service.repositories(user)).isInstanceOf(GithubUnavailableException.class);
	}

	@Test
	void t013_05_refreshNeverFallsBackToUnverifiedOldLogin() {
		when(client.userById(123)).thenThrow(new GithubNotFoundException());
		assertThatThrownBy(() -> service.refresh(user)).isInstanceOf(GithubUnavailableException.class);
		verify(client, never()).repositories(anyString(), anyLong(), any(), any());
	}

	private RepoRow selected() {
		return new RepoRow(localRepoId, 999, "old-project", null, "https://github.com/old-name/old-project", "Java",
				List.of(), false, false, now, true, List.of(), "languages-old-etag", now.minusSeconds(90000));
	}

	@Test
	void t013_05_selectionAlsoResolvesOwnerBeforeReadingLanguages() {
		when(store.findRepos(user)).thenReturn(List.of(selected()));
		when(client.languages("new-name", "old-project", null))
				.thenReturn(new Fetched<>(Map.of("Java", 100L), "new-language-etag", false));
		service.select(user, List.of(localRepoId.toString()));
		verify(client).userById(123);
		verify(client).languages("new-name", "old-project", null);
		verify(client, never()).languages(eq("old-name"), anyString(), any());
		verify(store).updateLogin(user, "new-name");
	}

	@Test
	void t013_09_renamedSelectedRepositoryUsesNewNameAndKeepsSelection() {
		when(store.findRepos(user)).thenReturn(List.of(selected()));
		var renamed = new RepoData(999, "new-project", null, "https://github.com/new-name/new-project", "Java",
				List.of(), false, false, now);
		when(client.repositories("new-name", 123, null, null)).thenReturn(new Fetched<>(List.of(renamed), "new-etag", false));
		when(client.languages("new-name", "new-project", null)).thenReturn(new Fetched<>(Map.of("Java", 100L), "new-language-etag", false));
		// La ruta anterior podría responder 301 o 404: nunca debe consultarse.
		when(client.languages(eq("new-name"), eq("old-project"), any())).thenThrow(new GithubNotFoundException());
		assertThat(service.refresh(user).removed()).isEmpty();
		verify(client).languages("new-name", "new-project", null);
		verify(client, never()).languages(eq("new-name"), eq("old-project"), any());
		verify(store).syncRepos(user, List.of(renamed));
		verify(store).saveLanguages(eq(user), eq(localRepoId), anyList(), eq("new-language-etag"), eq(now));
		verify(store, never()).deleteRepo(any(), any());
	}

	@Test
	void t013_09_unchangedListKeepsNamesAndLanguageEtags() {
		when(client.userById(123)).thenReturn(new GithubUser(123, "old-name", "User"));
		when(store.findRepos(user)).thenReturn(List.of(selected()));
		when(client.repositories("old-name", 123, null, "old-etag")).thenReturn(new Fetched<>(null, "old-etag", true));
		when(client.languages("old-name", "old-project", "languages-old-etag"))
				.thenReturn(new Fetched<>(null, "languages-old-etag", true));
		assertThat(service.refresh(user).removed()).isEmpty();
		verify(store).touchLanguages(user, localRepoId, now);
		verify(store, never()).deleteRepo(any(), any());
	}

	@Test
	void t013_09_disappearedRepositoryIsReportedWithoutQueryingOldPath() {
		when(store.findRepos(user)).thenReturn(List.of(selected()));
		assertThat(service.refresh(user).removed()).singleElement()
				.satisfies(removed -> assertThat(removed.id()).isEqualTo(localRepoId));
		verify(client, never()).languages(anyString(), anyString(), any());
		verify(store).syncRepos(user, List.of());
	}
}
