package com.talenti.shared.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Asigna a cada petición un identificador de correlación generado por el servidor (nunca el que mande el
 * cliente). Aparece en el registro y en {@code audit_event.correlation_id}; no es un dato personal y no
 * crea sesión ni cookie.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		MDC.put(Correlation.MDC_KEY, UUID.randomUUID().toString());
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(Correlation.MDC_KEY);
		}
	}

}
