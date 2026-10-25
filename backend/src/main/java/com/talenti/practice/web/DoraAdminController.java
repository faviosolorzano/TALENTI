package com.talenti.practice.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.talenti.practice.service.DoraErrors;
import com.talenti.practice.service.DoraService;
import com.talenti.practice.service.Views;

/** 012-15: p50 y p95 del tiempo hasta el primer audio de los últimos N turnos (rol ADMIN), sin contenido. */
@RestController
@RequestMapping(PracticeWebConfig.ADMIN)
class DoraAdminController {

	private final DoraService service;

	DoraAdminController(DoraService service) {
		this.service = service;
	}

	@GetMapping("/latency")
	Views.Latency latency(@RequestParam(required = false) String last) {
		int count = 100;
		if (last != null && !last.isBlank()) {
			try {
				count = Integer.parseInt(last.strip());
			}
			catch (NumberFormatException malformed) {
				throw DoraErrors.invalid("last", "invalid");
			}
			if (count < 1 || count > 1000) {
				throw DoraErrors.invalid("last", "out-of-range", 1, 1000);
			}
		}
		return service.latency(count);
	}

}
