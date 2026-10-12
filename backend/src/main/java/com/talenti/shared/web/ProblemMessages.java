package com.talenti.shared.web;

import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/** Textos en español de los errores, leídos de {@code messages.properties} (no van dentro de la lógica). */
@Component
public class ProblemMessages {

	private static final Locale SPANISH = Locale.forLanguageTag("es");

	private final MessageSource messageSource;

	ProblemMessages(MessageSource messageSource) {
		this.messageSource = messageSource;
	}

	public String title(String code) {
		return messageSource.getMessage("problem." + code + ".title", null, code, SPANISH);
	}

	public String detail(String code, Object... args) {
		return messageSource.getMessage("problem." + code + ".detail", args, "", SPANISH);
	}

	/** Texto de una regla incumplida: primero {@code validation.<campo>.<regla>}, luego {@code validation.<regla>}. */
	public String violation(FieldViolation violation) {
		Object[] args = violation.args().toArray();
		String generic = messageSource.getMessage("validation." + violation.rule(), args, violation.rule(), SPANISH);
		return messageSource.getMessage("validation." + violation.field() + "." + violation.rule(), args, generic,
				SPANISH);
	}

}
