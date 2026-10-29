package com.talenti.exam.snapshot;

import java.nio.file.Path;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.talenti.exam.config.ProctoringProperties;

/** 014-54: fotos en disco (volumen exam-snapshots) o en memoria solo para pruebas. Sin relación con la IA (014-55). */
@Configuration(proxyBeanMethods=false)
class SnapshotConfig {
	@Bean ExamSnapshotStorage examSnapshotStorage(ProctoringProperties p,Clock clock) {
		return p.storage().type().equals("memory")?new MemorySnapshotStorage(clock):new LocalSnapshotStorage(Path.of(p.storage().path()));
	}
}
