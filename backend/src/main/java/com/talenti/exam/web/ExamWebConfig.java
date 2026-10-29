package com.talenti.exam.web;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import com.talenti.identity.*;
import com.talenti.shared.web.*;
import jakarta.servlet.http.*;

@Configuration(proxyBeanMethods=false)
class ExamWebConfig implements WebMvcConfigurer {
	/** Margen del cuerpo multipart sobre el tamaño de la foto (cabeceras y campos de texto). */
	static final long SNAPSHOT_OVERHEAD=16*1024;
	private final Clock clock;
	private final long snapshotBody;
	ExamWebConfig(Clock clock,com.talenti.exam.config.ProctoringProperties proctoring) {
		this.clock=clock; this.snapshotBody=proctoring.snapshotMaxBytes().toBytes()+SNAPSHOT_OVERHEAD;
	}
	@Override public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new Access(Role.STUDENT,clock,snapshotBody)).addPathPatterns("/api/v1/exam-attempts/**","/api/v1/applications/*/exam");
		registry.addInterceptor(new Access(Role.ADMIN,clock,65536)).addPathPatterns("/api/v1/admin/vacancies/*/exam-set");
	}
	/** 014-54: una foto que ya declara demasiado se rechaza con 413 antes de leer el cuerpo (después de la seguridad). */
	@org.springframework.context.annotation.Bean
	org.springframework.boot.web.servlet.FilterRegistrationBean<SnapshotSizeFilter> examSnapshotSizeFilter(
			@org.springframework.beans.factory.annotation.Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
		var registration=new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new SnapshotSizeFilter(snapshotBody,resolver));
		registration.setName("examSnapshotSizeFilter");
		registration.addUrlPatterns("/api/v1/exam-attempts/*");
		return registration;
	}
	static boolean snapshotUpload(HttpServletRequest request) {
		return "POST".equals(request.getMethod())&&request.getRequestURI().matches("/api/v1/exam-attempts/[^/]+/snapshots/?");
	}
	static final class SnapshotSizeFilter extends org.springframework.web.filter.OncePerRequestFilter {
		private final long max;
		private final HandlerExceptionResolver resolver;
		SnapshotSizeFilter(long max,HandlerExceptionResolver resolver) { this.max=max; this.resolver=resolver; }
		@Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,jakarta.servlet.FilterChain chain)
				throws jakarta.servlet.ServletException,java.io.IOException {
			if(snapshotUpload(request)&&request.getContentLengthLong()>max) {
				response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
				if(resolver.resolveException(request,response,null,new ApiException(HttpStatus.CONTENT_TOO_LARGE,"snapshot-too-large"))==null)
					response.sendError(HttpStatus.CONTENT_TOO_LARGE.value());
				return;
			}
			chain.doFilter(request,response);
		}
	}
	private static final class Access implements HandlerInterceptor {
		private final Role role;
		private final Clock clock;
		private final long snapshotBody;
		private final Map<UUID,Deque<Instant>> saves=new ConcurrentHashMap<>();
		Access(Role role,Clock clock,long snapshotBody) { this.role=role; this.clock=clock; this.snapshotBody=snapshotBody; }
		@Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
			response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
			var auth=SecurityContextHolder.getContext().getAuthentication();
			if(auth==null||!(auth.getPrincipal() instanceof CurrentUser user)||user.role()!=role) throw new ApiException(HttpStatus.FORBIDDEN,"access-denied");
			if(snapshotUpload(request)) { if(request.getContentLengthLong()>snapshotBody) throw new ApiException(HttpStatus.CONTENT_TOO_LARGE,"snapshot-too-large"); }
			else if(request.getContentLengthLong()>65536) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"payload-too-large");
			if(request.getMethod().equals("PUT")) {
				Instant now=clock.instant();
				saves.compute(user.id(),(id,window)->{
					if(window==null) window=new ArrayDeque<>();
					while(!window.isEmpty()&&!window.getFirst().isAfter(now.minusSeconds(60))) window.removeFirst();
					if(window.size()>=60) throw new TooManyRequestsException(60);
					window.addLast(now); return window;
				});
			}
			return true;
		}
	}
}
