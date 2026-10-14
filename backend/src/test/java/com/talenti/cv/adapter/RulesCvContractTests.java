package com.talenti.cv.adapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.core.io.ClassPathResource;

import com.talenti.cv.CvExtractionPort;
import com.talenti.cv.domain.CareerDictionary;

/** 009-11: el adaptador de reglas cumple el contrato del puerto. */
class RulesCvContractTests extends CvExtractionContractTests {

	@Override
	protected CvExtractionPort adapter() {
		try {
			return new RulesCvExtractionAdapter(new CareerDictionary(List.of(
					new String(new ClassPathResource("cv/careers.txt").getInputStream().readAllBytes(),
							StandardCharsets.UTF_8).split("\\R"))));
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

}
