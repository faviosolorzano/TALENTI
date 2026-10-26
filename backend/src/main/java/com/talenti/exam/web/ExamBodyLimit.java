package com.talenti.exam.web;

import java.io.*;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import com.talenti.shared.web.ApiException;

/** Cuenta bytes antes de deserializar JSON, incluso sin Content-Length. */
@ControllerAdvice(assignableTypes=ExamController.class)
class ExamBodyLimit extends RequestBodyAdviceAdapter {
	static final int MAX_BYTES=65536;
	@Override public boolean supports(MethodParameter parameter,Type target,Class<? extends HttpMessageConverter<?>> converter) { return true; }
	@Override public HttpInputMessage beforeBodyRead(HttpInputMessage input,MethodParameter parameter,Type target,
			Class<? extends HttpMessageConverter<?>> converter) throws IOException {
		byte[] bytes=input.getBody().readNBytes(MAX_BYTES+1);
		if(bytes.length>MAX_BYTES) throw new ApiException(HttpStatus.CONTENT_TOO_LARGE,"payload-too-large");
		return new HttpInputMessage() {
			@Override public InputStream getBody() { return new ByteArrayInputStream(bytes); }
			@Override public HttpHeaders getHeaders() { return input.getHeaders(); }
		};
	}
}
