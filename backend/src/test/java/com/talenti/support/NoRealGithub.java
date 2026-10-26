package com.talenti.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Guardia de las pruebas de GitHub (spec 013, sección 9): si algo intenta salir a {@code github.com} o a uno de sus
 * subdominios (por ejemplo {@code api.github.com}), lo anota y lo manda a un puerto local cerrado, así la petición
 * nunca llega a GitHub real. Las pruebas comprueban al final que no hubo intentos. El resto de los destinos sigue
 * con el selector de proxy que ya había.
 */
public final class NoRealGithub extends ProxySelector {

	private static final List<String> ATTEMPTS = new CopyOnWriteArrayList<>();

	private static NoRealGithub installed;

	private final ProxySelector previous;

	private NoRealGithub(ProxySelector previous) {
		this.previous = previous;
	}

	public static synchronized void install() {
		if (installed == null) {
			installed = new NoRealGithub(ProxySelector.getDefault());
			ProxySelector.setDefault(installed);
		}
	}

	/** Los destinos de GitHub real que alguien intentó usar (vacío = bien). */
	public static List<String> attempts() {
		return List.copyOf(ATTEMPTS);
	}

	public static void forget() {
		ATTEMPTS.clear();
	}

	static boolean isGithub(String host) {
		String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
		return h.equals("github.com") || h.endsWith(".github.com");
	}

	@Override
	public List<Proxy> select(URI uri) {
		if (isGithub(uri.getHost())) {
			ATTEMPTS.add(uri.getHost());
			return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 9)));
		}
		return previous == null ? List.of(Proxy.NO_PROXY) : previous.select(uri);
	}

	@Override
	public void connectFailed(URI uri, SocketAddress address, IOException failure) {
		if (previous != null && !isGithub(uri.getHost())) {
			previous.connectFailed(uri, address, failure);
		}
	}

}
