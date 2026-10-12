package com.talenti.identity.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;

import com.talenti.identity.service.RateLimiter;

/** Cuenta cada solicitud por origen en su cubo, antes de leer el cuerpo (001-11). */
class RateLimitInterceptor implements HandlerInterceptor {

	private final RateLimiter limiter;

	RateLimitInterceptor(RateLimiter limiter) {
		this.limiter = limiter;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		limiter.check(ClientOrigin.of(request));
		return true;
	}

}
