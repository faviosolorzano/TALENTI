package com.talenti.github.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 013-25 y 013-30: el {@code state} y la dirección de autorización (código puro). */
class OAuthStateTests {

	@Test
	@DisplayName("013-25: el state son 32 bytes al azar en Base64URL sin relleno, distinto cada vez")
	void t013_25_stateIs32RandomBytes() {
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 200; i++) {
			String state = OAuthState.newState();
			assertThat(state).matches("[A-Za-z0-9_-]{43}");
			assertThat(Base64.getUrlDecoder().decode(state)).hasSize(32);
			assertThat(seen.add(state)).isTrue();
		}
	}

	@Test
	@DisplayName("013-25: se guarda el hash SHA-256 (64 hexadecimales), nunca el state")
	void t013_25_hashIsSha256() {
		String state = OAuthState.newState();

		assertThat(OAuthState.sha256(state)).matches("[0-9a-f]{64}").isNotEqualTo(state)
				.isEqualTo(OAuthState.sha256(state));
		assertThat(OAuthState.sha256("abc"))
				.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
	}

	@Test
	@DisplayName("013-30: la dirección de autorización no lleva scope y lleva client_id, redirect_uri codificada, state y allow_signup=false")
	void t013_30_authorizeUrlHasNoScope() {
		String url = OAuthState.authorizeUrl("https://github.com/", "Iv1.abc", "http://localhost:3000/api/v1/github/oauth/callback",
				"st-ate_1");

		assertThat(url).isEqualTo("https://github.com/login/oauth/authorize?client_id=Iv1.abc&redirect_uri="
				+ "http%3A%2F%2Flocalhost%3A3000%2Fapi%2Fv1%2Fgithub%2Foauth%2Fcallback&state=st-ate_1&allow_signup=false");
		assertThat(url).doesNotContain("scope");
	}

}
