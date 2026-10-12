package com.talenti.identity.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.talenti.identity.ConsentPurpose;

public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, UUID> {

	/** El permiso más reciente de una finalidad. */
	Optional<ConsentRecord> findFirstByUserIdAndPurposeOrderByAcceptedAtDesc(UUID userId, ConsentPurpose purpose);

}
