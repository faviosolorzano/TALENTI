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

import com.talenti.identity.Role;

/** Usuario de la plataforma. Tabla {@code app_user}: solo el módulo identity la lee o la escribe. */
@Entity
@Table(name = "app_user")
public class AppUser {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Role role;

	@Column(name = "failed_login_count", nullable = false)
	private int failedLoginCount;

	@Column(name = "locked_until")
	private Instant lockedUntil;

	/** Sin uso todavía (001-24): nadie queda bloqueado por no tener el correo verificado. */
	@Column(name = "email_verified_at")
	private Instant emailVerifiedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected AppUser() {
	}

	private AppUser(String email, String passwordHash, Role role, Instant now) {
		this.email = email;
		this.passwordHash = passwordHash;
		this.role = role;
		this.createdAt = now;
		this.updatedAt = now;
	}

	/** El registro público siempre crea estudiantes (001-07). */
	public static AppUser newStudent(String normalizedEmail, String passwordHash, Instant now) {
		return new AppUser(normalizedEmail, passwordHash, Role.STUDENT, now);
	}

	public LockState lockState() {
		return new LockState(failedLoginCount, lockedUntil);
	}

	public void applyLockState(LockState state, Instant now) {
		this.failedLoginCount = state.failedCount();
		this.lockedUntil = state.lockedUntil();
		this.updatedAt = now;
	}

	public UUID getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public Role getRole() {
		return role;
	}

	public int getFailedLoginCount() {
		return failedLoginCount;
	}

	public Instant getLockedUntil() {
		return lockedUntil;
	}

	public Instant getEmailVerifiedAt() {
		return emailVerifiedAt;
	}

	@Override
	public String toString() {
		return "AppUser[id=" + id + ", role=" + role + "]";
	}

}
