package com.talenti.identity.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;

import com.talenti.identity.service.LoginRateLimiter;

/** Cuenta cada solicitud de ingreso por origen, antes de leer el cuerpo (001-11). */
class LoginRateLimitInterceptor implements HandlerInterceptor {

	private final LoginRateLimiter limiter;

	LoginRateLimitInterceptor(LoginRateLimiter limiter) {
		this.limiter = limiter;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		limiter.check(ClientOrigin.of(request));
		return true;
	}

}
