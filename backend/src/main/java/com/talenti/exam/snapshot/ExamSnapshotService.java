package com.talenti.exam.snapshot;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import com.talenti.exam.ExamProctoring;
import com.talenti.exam.config.ProctoringProperties;
import com.talenti.exam.domain.AttemptStatus;
import com.talenti.exam.domain.ProctoringRules.SnapshotReason;
import com.talenti.exam.persistence.ExamStore;
import com.talenti.exam.persistence.ExamStore.AttemptRow;
import com.talenti.exam.service.ExamErrors;
import com.talenti.shared.events.ApplicationWithdrawn;
import com.talenti.shared.web.ApiException;

/**
 * Fotos del examen (014-53 a 014-57). Solo las leen el propio proceso del examen, la propia persona y, por
 * {@link ExamProctoring}, la futura empresa de esa vacante. Nunca van a la IA ni a otro proveedor (014-55): esta clase
 * no conoce ningún puerto de IA (lo vigila ExamArchitectureTests). Los registros solo llevan ids y conteos.
 */
@Service
public class ExamSnapshotService implements ExamProctoring {
	private static final Logger log=LoggerFactory.getLogger(ExamSnapshotService.class);
	private static final int PURGE_BATCH=500;
	private final ExamStore exams;
	private final SnapshotStore store;
	private final ExamSnapshotStorage storage;
	private final ProctoringProperties properties;
	private final Clock clock;
	private final TransactionTemplate tx;
	public ExamSnapshotService(ExamStore exams,SnapshotStore store,ExamSnapshotStorage storage,ProctoringProperties properties,
			Clock clock,PlatformTransactionManager manager) {
		this.exams=exams; this.store=store; this.storage=storage; this.properties=properties; this.clock=clock; this.tx=new TransactionTemplate(manager);
	}
	public record Uploaded(UUID id,Instant takenAt) { }
	public record View(UUID id,String reason,Instant takenAt,Instant clientTakenAt,UUID eventClientId,int width,int height,int byteSize) { }

	/** 014-54. El orden de las comprobaciones sigue la spec: intento, tamaño, tipo, medidas, límite. */
	public Uploaded upload(UUID user,UUID attemptId,byte[] image,String reasonValue,String takenAtValue,String eventValue) {
		SnapshotReason reason; Instant clientTakenAt; UUID eventClientId=null;
		try { reason=SnapshotReason.valueOf(Objects.requireNonNull(reasonValue)); }
		catch(RuntimeException e) { throw ExamErrors.invalid("reason","invalid-snapshot-reason"); }
		try { clientTakenAt=Instant.parse(Objects.requireNonNull(takenAtValue)); }
		catch(NullPointerException|DateTimeParseException e) { throw ExamErrors.invalid("clientTakenAt","invalid-instant"); }
		if(eventValue!=null&&!eventValue.isBlank()) {
			try { if(eventValue.length()!=36) throw new IllegalArgumentException(); eventClientId=UUID.fromString(eventValue); }
			catch(IllegalArgumentException e) { throw ExamErrors.invalid("eventClientId","invalid-uuid"); }
		}
		if((reason==SnapshotReason.EVENT)!=(eventClientId!=null)) throw ExamErrors.invalid("eventClientId","event-client-id-mismatch");
		requireOpen(exams.ownedAttempt(attemptId,user,false).orElseThrow(ExamErrors::missing));
		SnapshotImages.Clean clean;
		try { clean=SnapshotImages.clean(image,properties.snapshotMaxBytes().toBytes(),properties.snapshotMaxSide()); }
		catch(SnapshotImages.Rejected r) {
			throw switch(r.problem()) {
				case EMPTY -> ExamErrors.invalid("image","required");
				case TOO_LARGE -> new ApiException(HttpStatus.CONTENT_TOO_LARGE,"snapshot-too-large");
				case NOT_JPEG -> new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"snapshot-not-jpeg");
				case UNREADABLE -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"snapshot-unreadable");
				case DIMENSIONS -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"snapshot-dimensions");
			};
		}
		UUID id=UUID.randomUUID(); String key=ExamSnapshotStorage.key(attemptId,id);
		final SnapshotReason r=reason; final Instant taken=clientTakenAt; final UUID event=eventClientId;
		boolean[] written={false};
		try {
			Uploaded result=tx.execute(status->{
				AttemptRow a=exams.ownedAttempt(attemptId,user,true).orElseThrow(ExamErrors::missing);
				requireOpen(a);
				if(store.count(attemptId)>=properties.maxSnapshotsPerAttempt()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"snapshot-limit");
				Instant now=clock.instant();
				storage.put(key,clean.jpeg()); written[0]=true;
				store.insert(new SnapshotStore.Row(id,attemptId,r.name(),event,key,clean.jpeg().length,clean.width(),clean.height(),clean.sha256(),
						taken,now,a.deadlineAt().plus(properties.retention())));
				return new Uploaded(id,now);
			});
			log.debug("Foto del examen guardada: intento {}, motivo {}",attemptId,reason);
			return result;
		}
		catch(RuntimeException e) { if(written[0]) deleteQuietly(key); throw e; }
	}
	private void requireOpen(AttemptRow a) {
		if(a.status()!=AttemptStatus.IN_PROGRESS||a.deadlineAt()==null||!clock.instant().isBefore(a.deadlineAt())) throw ExamErrors.conflict("exam-closed");
		if(a.proctoringVersion()==null) throw ExamErrors.conflict("proctoring-not-active");
	}
	/** 014-55, duda E5: la propia persona ve sus fotos en cualquier estado del intento. */
	public List<View> list(UUID user,UUID attemptId) {
		exams.ownedAttempt(attemptId,user,false).orElseThrow(ExamErrors::missing);
		return store.forAttempt(attemptId).stream().map(s->new View(s.id(),s.reason(),s.createdAt(),s.clientTakenAt(),s.eventClientId(),s.width(),s.height(),s.byteSize())).toList();
	}
	public byte[] open(UUID user,UUID attemptId,UUID snapshotId) {
		exams.ownedAttempt(attemptId,user,false).orElseThrow(ExamErrors::missing);
		var row=store.find(attemptId,snapshotId).orElseThrow(ExamErrors::missing);
		return storage.read(row.storageKey()).orElseThrow(ExamErrors::missing);
	}
	@Override public Optional<Image> openSnapshot(UUID applicationId,UUID snapshotId) {
		return exams.forApplication(applicationId).flatMap(a->store.find(a.id(),snapshotId))
				.flatMap(row->storage.read(row.storageKey()).map(bytes->new Image(row.id(),row.createdAt(),row.reason(),bytes)));
	}
	/** 014-56: al terminar el intento, el plazo corre desde su fin. Se llama dentro de la transacción del cierre. */
	public void scheduleFromEnd(UUID attemptId,Instant endedAt) { store.schedulePurge(attemptId,endedAt.plus(properties.retention())); }

	/** 014-56, duda E6: retirar la postulación borra sus fotos (fichas y archivos). */
	@TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT,fallbackExecution=true)
	public void withdrawn(ApplicationWithdrawn event) {
		exams.forApplication(event.applicationId()).ifPresent(a->deleteForAttempt(a.id()));
	}
	public int deleteForAttempt(UUID attemptId) {
		List<String> keys=tx.execute(status->store.deleteForAttempt(attemptId));
		keys.forEach(this::deleteQuietly);
		if(!keys.isEmpty()) log.info("Fotos del examen borradas por retiro: intento {}, {} fotos",attemptId,keys.size());
		return keys.size();
	}
	/** Tarea diaria (014-56): borra lo vencido y los archivos sin ficha (cuenta eliminada o subida interrumpida). */
	@Scheduled(fixedDelayString="${talenti.exam.proctoring.purge-interval:PT24H}",initialDelayString="${talenti.exam.proctoring.purge-initial-delay:PT10M}")
	public void scheduledPurge() {
		try { purgeExpired(); sweepOrphans(); }
		catch(RuntimeException e) { log.warn("No se completó la limpieza de fotos del examen: {}",e.getClass().getSimpleName()); }
	}
	public int purgeExpired() {
		int total=0;
		while(true) {
			var rows=store.expired(clock.instant(),PURGE_BATCH);
			for(var row:rows) if(store.delete(row.id())) { deleteQuietly(row.storageKey()); total++; }
			if(rows.size()<PURGE_BATCH) break;
		}
		if(total>0) log.info("Fotos del examen vencidas borradas: {}",total);
		return total;
	}
	public int sweepOrphans() {
		Instant limit=clock.instant().minus(properties.orphanGrace());
		var candidates=storage.list().stream().filter(s->!s.modifiedAt().isAfter(limit)).map(ExamSnapshotStorage.Stored::key).toList();
		int removed=0;
		for(int i=0;i<candidates.size();i+=PURGE_BATCH) {
			var chunk=candidates.subList(i,Math.min(candidates.size(),i+PURGE_BATCH));
			var existing=store.existingKeys(chunk);
			for(String key:chunk) if(!existing.contains(key)) { deleteQuietly(key); removed++; }
		}
		if(removed>0) log.info("Fotos del examen sin ficha borradas: {}",removed);
		return removed;
	}
	private void deleteQuietly(String key) {
		try { storage.delete(key); }
		catch(RuntimeException e) { log.warn("No se pudo borrar una foto del examen; la retirará el barrido: {}",e.getClass().getSimpleName()); }
	}
}
