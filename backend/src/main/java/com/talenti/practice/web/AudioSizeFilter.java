package com.talenti.practice.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.talenti.shared.web.ApiException;

/**
 * Rechaza con 413 {@code audio-too-large} un turno cuyo {@code Content-Length} ya declara demasiado, ANTES de leer el
 * cuerpo y de abrir el flujo (012-07). Corre después de la cadena de seguridad. Lo que declara poco pero trae más lo
 * detiene el límite de Spring y, al final, el servicio (que mide el audio real).
 */
class AudioSizeFilter extends OncePerRequestFilter {

	private final long maxBytes;

	private final HandlerExceptionResolver exceptionResolver;

	AudioSizeFilter(long maxBytes, HandlerExceptionResolver exceptionResolver) {
		this.maxBytes = maxBytes;
		this.exceptionResolver = exceptionResolver;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if ("POST".equals(request.getMethod()) && request.getRequestURI().endsWith("/turns")
				&& request.getContentLengthLong() > maxBytes) {
			response.setHeader("Cache-Control", "no-store");
			if (exceptionResolver.resolveException(request, response, null,
					new ApiException(HttpStatus.CONTENT_TOO_LARGE, "audio-too-large")) == null) {
				response.sendError(HttpStatus.CONTENT_TOO_LARGE.value());
			}
			return;
		}
		chain.doFilter(request, response);
	}

}
