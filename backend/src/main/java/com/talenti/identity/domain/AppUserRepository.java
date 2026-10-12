package com.talenti.identity.domain;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

	Optional<AppUser> findByEmail(String email);

	boolean existsByEmail(String email);

	/** Bloquea la fila mientras dura la transacción: el contador de intentos se actualiza sin pérdidas. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from AppUser u where u.id = :id")
	Optional<AppUser> findByIdForUpdate(@Param("id") UUID id);

}
