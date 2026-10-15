package com.talenti.cv;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.talenti.vacancy.SkillInfo;

/**
 * Adaptador de IA SIMULADO con contador de llamadas, para comprobar cuándo se llama a la IA y cuándo no (009-25 a 009-32).
 * No hace ninguna llamada de red. Devuelve una habilidad y un proyecto; puede fallar o tardar a pedido.
 */
public class CountingAiAdapter implements EnhancedCvExtractionPort {

	public enum Mode {
		OK, FAIL, SLOW
	}

	public final AtomicInteger calls = new AtomicInteger();

	public volatile int lastInputLength;

	public volatile String lastInput = "";

	public volatile Mode mode = Mode.OK;

	/** Cuánto tarda en el modo lento y cuando se pide una demora al responder. */
	public volatile long delayMillis = 0;

	public void reset() {
		calls.set(0);
		lastInputLength = 0;
		lastInput = "";
		mode = Mode.OK;
		delayMillis = 0;
	}

	@Override
	public AdapterInfo info() {
		return new AdapterInfo("simulated-ai", "1.0.0", "test-instructions-1", "modelo-simulado");
	}

	@Override
	public List<RawSuggestion> extract(String reducedText, List<SkillInfo> catalog) {
		calls.incrementAndGet();
		lastInputLength = reducedText.length();
		lastInput = reducedText;
		try {
			if (mode == Mode.SLOW) {
				Thread.sleep(10_000);
			}
			else if (delayMillis > 0) {
				Thread.sleep(delayMillis);
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CvExtractionException("interrumpido");
		}
		if (mode == Mode.FAIL) {
			throw new CvExtractionException("falla simulada de la plataforma");
		}
		// La evidencia debe aparecer de verdad en el texto leído (T3): se toma una frase del propio texto.
		String phrase = reducedText.lines().map(String::strip).filter(line -> !line.isEmpty()).findFirst().orElse("");
		phrase = phrase.length() > 60 ? phrase.substring(0, 60).strip() : phrase;
		List<RawSuggestion> result = new ArrayList<>();
		result.add(RawSuggestion.skill("java", "Java", 2, phrase));
		result.add(RawSuggestion.skill("typescript", "TypeScript", 1, phrase));
		result.add(RawSuggestion.academic("CAREER", "Ingeniería de Software", phrase));
		return result;
	}

	/** Registra el adaptador simulado como el adaptador de IA de la aplicación de prueba. */
	@TestConfiguration(proxyBeanMethods = false)
	public static class Configuration {

		@Bean
		CountingAiAdapter countingAiAdapter() {
			return new CountingAiAdapter();
		}

	}

}
