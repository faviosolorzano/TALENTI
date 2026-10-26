package com.talenti.exam.service;

import java.util.List;
import org.springframework.http.HttpStatus;
import com.talenti.shared.web.*;

public final class ExamErrors {
	private ExamErrors() { }
	public static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"exam-not-found"); }
	public static ApiException conflict(String code) { return new ApiException(HttpStatus.CONFLICT,code); }
	public static ApiException unavailable(String code) { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,code,java.util.Map.of("retryable",true)); }
	public static ApiException invalid(String field,String code,Object... args) { return new ValidationFailedException(List.of(FieldViolation.of(field,code,args))); }
}
