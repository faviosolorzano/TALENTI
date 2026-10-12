package com.talenti.identity.web;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.talenti.identity.CurrentUser;
import com.talenti.identity.service.ConsentService;
import com.talenti.identity.service.LegalCatalog;
import com.talenti.identity.service.LegalDocumentService;

/** Documentos legales vigentes (públicos) y aceptación de una versión nueva (con sesión). */
@RestController
@RequestMapping(IdentityWebConfig.API + "/legal")
class LegalController {

	private final LegalDocumentService legal;

	private final ConsentService consents;

	LegalController(LegalDocumentService legal, ConsentService consents) {
		this.legal = legal;
		this.consents = consents;
	}

	@GetMapping("/current")
	LegalCatalog current() {
		return legal.current();
	}

	/**
	 * Acepta {@code TERMS} y {@code ACCOUNT_DATA} de la versión vigente (001-30). En el E2 el servidor no
	 * bloquea el resto de la API mientras haya una aceptación pendiente: ese control es de la interfaz.
	 */
	@PostMapping("/accept")
	ResponseEntity<Void> accept(@AuthenticationPrincipal CurrentUser current, @RequestBody AcceptLegalRequest body) {
		consents.acceptCurrentDocuments(current.id(), body.legalAccepted(), body.legalVersion());
		return ResponseEntity.noContent().build();
	}

}
