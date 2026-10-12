package com.talenti.identity.service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.talenti.identity.ConsentPurpose;
import com.talenti.identity.domain.ConsentRecord;
import com.talenti.identity.domain.ConsentRecordRepository;
import com.talenti.shared.audit.AuditEvent;
import com.talenti.shared.audit.AuditRecorder;
import com.talenti.shared.web.ValidationFailedException;

/** Permisos aceptados por una persona (RF-47). Solo inserta: nunca modifica un permiso anterior. */
@Service
public class ConsentService {

	public static final String CONSENT_ACCEPTED = "CONSENT_ACCEPTED";

	/** Los dos permisos que se aceptan con la casilla única de los documentos legales. */
	static final List<ConsentPurpose> ACCOUNT_PURPOSES = List.of(ConsentPurpose.TERMS, ConsentPurpose.ACCOUNT_DATA);

	private final ConsentRecordRepository consents;

	private final AuditRecorder audit;

	private final LegalDocumentService legal;

	private final Clock clock;

	ConsentService(ConsentRecordRepository consents, AuditRecorder audit, LegalDocumentService legal, Clock clock) {
		this.consents = consents;
		this.audit = audit;
		this.legal = legal;
		this.clock = clock;
	}

	/**
	 * Guarda {@code TERMS} y {@code ACCOUNT_DATA} con la versión y la fecha, y audita cada uno. Se une a la
	 * transacción que esté activa (el registro lo llama dentro de la suya, 001-06).
	 */
	@Transactional
	public void acceptAccountDocuments(UUID userId, String version) {
		for (ConsentPurpose purpose : ACCOUNT_PURPOSES) {
			ConsentRecord record = consents.save(new ConsentRecord(userId, purpose, version, clock.instant()));
			audit.record(new AuditEvent(CONSENT_ACCEPTED, userId, "consent_record", record.getId(),
					Map.of("purpose", purpose.name(), "version", version)));
		}
	}

	/** Aceptar una versión nueva de los documentos estando ya con sesión (001-30). */
	@Transactional
	public void acceptCurrentDocuments(UUID userId, Boolean legalAccepted, String legalVersion) {
		var violations = legal.validateAcceptance(legalAccepted, legalVersion);
		if (!violations.isEmpty()) {
			throw new ValidationFailedException(violations);
		}
		legal.requireCurrentVersion(legalVersion);
		acceptAccountDocuments(userId, legalVersion);
	}

	/** Verdadero si la última versión aceptada de {@code TERMS} o de {@code ACCOUNT_DATA} no es la vigente (001-29). */
	@Transactional(readOnly = true)
	public boolean isAcceptancePending(UUID userId) {
		String current = legal.currentVersion();
		return ACCOUNT_PURPOSES.stream().anyMatch(purpose -> consents
				.findFirstByUserIdAndPurposeOrderByAcceptedAtDesc(userId, purpose)
				.map(record -> !current.equals(record.getConsentVersion()))
				.orElse(true));
	}

}
