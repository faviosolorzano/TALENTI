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
	private final Clock clock;
	ExamWebConfig(Clock clock) { this.clock=clock; }
	@Override public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new Access(Role.STUDENT,clock)).addPathPatterns("/api/v1/exam-attempts/**","/api/v1/applications/*/exam");
		registry.addInterceptor(new Access(Role.ADMIN,clock)).addPathPatterns("/api/v1/admin/vacancies/*/exam-set");
	}
	private static final class Access implements HandlerInterceptor {
		private final Role role;
		private final Clock clock;
		private final Map<UUID,Deque<Instant>> saves=new ConcurrentHashMap<>();
		Access(Role role,Clock clock) { this.role=role; this.clock=clock; }
		@Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
			response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
			var auth=SecurityContextHolder.getContext().getAuthentication();
			if(auth==null||!(auth.getPrincipal() instanceof CurrentUser user)||user.role()!=role) throw new ApiException(HttpStatus.FORBIDDEN,"access-denied");
			if(request.getContentLengthLong()>65536) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"payload-too-large");
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
