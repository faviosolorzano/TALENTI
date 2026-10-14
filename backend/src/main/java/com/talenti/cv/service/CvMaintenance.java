package com.talenti.cv.service;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.talenti.cv.persistence.CvRepository;
import com.talenti.shared.audit.AuditEvent;
import com.talenti.shared.audit.AuditRecorder;

/**
 * Tareas de mantenimiento del lector de CV: al arrancar, lo que quedó a medias por un reinicio pasa a FAILED
 * (009-08, 009-32); cada hora, lo vencido (7 días) se expira, se borra su contenido y se audita {@code CV_EXPIRED}
 * (009-10). Ninguna de las dos lee un CV ni llama a una IA (009-25).
 */
@Component
public class CvMaintenance {

	private static final Logger log = LoggerFactory.getLogger(CvMaintenance.class);

	private final CvRepository repository;

	private final AuditRecorder audit;

	private final TransactionTemplate transaction;

	private final Clock clock;

	CvMaintenance(CvRepository repository, AuditRecorder audit, PlatformTransactionManager transactionManager,
			Clock clock) {
		this.repository = repository;
		this.audit = audit;
		this.transaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void failOrphans() {
		int orphans = repository.failOrphans();
		if (orphans > 0) {
			log.info("{} análisis de CV quedaron a medias por un reinicio y pasaron a FAILED", orphans);
		}
	}

	/** El intervalo sale de {@code talenti.cv.expiry-interval}; las pruebas lo alargan para llamar al método a mano. */
	@Scheduled(fixedDelayString = "${talenti.cv.expiry-interval:PT1H}",
			initialDelayString = "${talenti.cv.expiry-interval:PT1H}")
	public int expireOverdue() {
		List<CvRepository.Expired> expired = transaction.execute(status -> {
			List<CvRepository.Expired> items = repository.expireOverdue(clock.instant());
			for (CvRepository.Expired item : items) {
				audit.record(new AuditEvent("CV_EXPIRED", item.userId(), "cv_analysis", item.id(), Map.of()));
			}
			return items;
		});
		int count = expired == null ? 0 : expired.size();
		if (count > 0) {
			log.info("{} análisis de CV vencieron y se borró su contenido", count);
		}
		return count;
	}

}
