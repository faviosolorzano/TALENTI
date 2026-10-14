package com.talenti.cv.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.talenti.cv.service.CvErrors;

/**
 * Rechaza con 413 {@code cv-too-large} una subida cuyo {@code Content-Length} ya declara demasiado, ANTES de que
 * Spring la lea (009-05). Corre después de la cadena de seguridad: sin sesión sigue siendo 401 y sin CSRF, 403. Lo
 * que declara poco pero trae más lo detiene el límite de Spring y, al final, el servicio (que mide el archivo real).
 */
class CvUploadSizeFilter extends OncePerRequestFilter {

	private final long maxBytes;

	private final HandlerExceptionResolver exceptionResolver;

	CvUploadSizeFilter(long maxBytes, HandlerExceptionResolver exceptionResolver) {
		this.maxBytes = maxBytes;
		this.exceptionResolver = exceptionResolver;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if ("POST".equals(request.getMethod()) && request.getContentLengthLong() > maxBytes) {
			response.setHeader("Cache-Control", "no-store");
			if (exceptionResolver.resolveException(request, response, null, CvErrors.tooLarge()) == null) {
				response.sendError(HttpStatus.CONTENT_TOO_LARGE.value());
			}
			return;
		}
		chain.doFilter(request, response);
	}

}
