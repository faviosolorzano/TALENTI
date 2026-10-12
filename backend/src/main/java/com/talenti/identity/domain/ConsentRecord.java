package com.talenti.identity.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.talenti.identity.ConsentPurpose;

/** Un permiso aceptado: finalidad, versión del texto y fecha. Solo se inserta, nunca se actualiza. */
@Entity
@Table(name = "consent_record")
public class ConsentRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private ConsentPurpose purpose;

	@Column(name = "consent_version", nullable = false, updatable = false)
	private String consentVersion;

	@Column(name = "accepted_at", nullable = false, updatable = false)
	private Instant acceptedAt;

	protected ConsentRecord() {
	}

	public ConsentRecord(UUID userId, ConsentPurpose purpose, String consentVersion, Instant acceptedAt) {
		this.userId = userId;
		this.purpose = purpose;
		this.consentVersion = consentVersion;
		this.acceptedAt = acceptedAt;
	}

	public UUID getId() {
		return id;
	}

	public UUID getUserId() {
		return userId;
	}

	public ConsentPurpose getPurpose() {
		return purpose;
	}

	public String getConsentVersion() {
		return consentVersion;
	}

	public Instant getAcceptedAt() {
		return acceptedAt;
	}

}
