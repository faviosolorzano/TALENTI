package com.talenti.profile.web;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.talenti.shared.web.ApiException;

/**
 * Tope de tamaño del cuerpo de {@code /profile} que también vale cuando no hay {@code Content-Length} (cuerpo
 * {@code chunked}). Lee el cuerpo de las escrituras hasta el límite más un byte (nunca guarda más que eso en memoria):
 * si lo pasa, responde 413 {@code payload-too-large} como Problem Details sin ejecutar el controlador; si no, entrega
 * el cuerpo ya leído. El interceptor rechaza antes, sin leer nada, si el {@code Content-Length} ya declara demasiado.
 */
class BodyLimitFilter extends OncePerRequestFilter {

	private final long maxBytes;

	private final HandlerExceptionResolver exceptionResolver;

	BodyLimitFilter(long maxBytes, HandlerExceptionResolver exceptionResolver) {
		this.maxBytes = maxBytes;
		this.exceptionResolver = exceptionResolver;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String method = request.getMethod();
		if (!"PUT".equals(method) && !"POST".equals(method)) {
			chain.doFilter(request, response);
			return;
		}
		byte[] body = request.getInputStream().readNBytes((int) maxBytes + 1);
		if (body.length > maxBytes) {
			response.setHeader("Cache-Control", "no-store");
			if (exceptionResolver.resolveException(request, response, null,
					new ApiException(HttpStatus.CONTENT_TOO_LARGE, "payload-too-large")) == null) {
				response.sendError(HttpStatus.CONTENT_TOO_LARGE.value());
			}
			return;
		}
		chain.doFilter(new BufferedRequest(request, body), response);
	}

	/** La misma solicitud, con el cuerpo ya leído en memoria. */
	private static final class BufferedRequest extends HttpServletRequestWrapper {

		private final byte[] body;

		private BufferedRequest(HttpServletRequest request, byte[] body) {
			super(request);
			this.body = body;
		}

		@Override
		public ServletInputStream getInputStream() {
			ByteArrayInputStream bytes = new ByteArrayInputStream(body);
			return new ServletInputStream() {

				@Override
				public int read() {
					return bytes.read();
				}

				@Override
				public int read(byte[] buffer, int offset, int length) {
					return bytes.read(buffer, offset, length);
				}

				@Override
				public boolean isFinished() {
					return bytes.available() == 0;
				}

				@Override
				public boolean isReady() {
					return true;
				}

				@Override
				public void setReadListener(ReadListener listener) {
					throw new UnsupportedOperationException();
				}

			};
		}

		@Override
		public BufferedReader getReader() {
			String encoding = getCharacterEncoding();
			Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
			return new BufferedReader(new InputStreamReader(getInputStream(), charset));
		}

		@Override
		public int getContentLength() {
			return body.length;
		}

		@Override
		public long getContentLengthLong() {
			return body.length;
		}

	}

}
