package com.talenti.exam.adapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import com.talenti.exam.config.ExamProperties;
import com.talenti.exam.domain.ExamQuestion;
import com.talenti.exam.port.CodeExecutionPort;
import tools.jackson.databind.json.JsonMapper;

/** El backend solo escribe/lee trabajos. Nunca ejecuta código ni accede al socket de Docker. */
public final class VolumeCodeExecution implements CodeExecutionPort {
	private final Path root;
	private final Semaphore slots;
	private final ExamProperties.Runner properties;
	private final JsonMapper json;
	public VolumeCodeExecution(ExamProperties.Runner properties,JsonMapper json) {
		this.properties=properties; this.json=json; this.root=Path.of(properties.jobs()).toAbsolutePath().normalize();
		this.slots=new Semaphore(properties.maxParallel(),true);
	}
	@Override public List<Result> run(ExamQuestion.Language language,String code,List<ExamQuestion.Case> cases,Limits limits) {
		if(language==ExamQuestion.Language.SQL||code==null||code.getBytes(StandardCharsets.UTF_8).length>limits.codeBytes()) throw new Unavailable();
		boolean acquired=false; Path job=null;
		try {
			acquired=slots.tryAcquire(properties.queueTimeout().toMillis(),TimeUnit.MILLISECONDS);
			if(!acquired) throw new Unavailable();
			if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(root)) throw new Unavailable();
			job=root.resolve(UUID.randomUUID().toString());
			Files.createDirectory(job);
			if(Files.getFileStore(job).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(job,PosixFilePermissions.fromString("rwx------"));
			Path pending=job.resolve("request.tmp");
			Files.writeString(pending,json.writeValueAsString(Map.of("language",language.name(),"code",code,
					"inputs",cases.stream().map(c->Objects.toString(c.input(),"")).toList(),"limits",limits)),StandardOpenOption.CREATE_NEW);
			Files.move(pending,job.resolve("request.json"),StandardCopyOption.ATOMIC_MOVE);
			long maximum=properties.queueTimeout().toNanos()+limits.compileTimeout().toNanos()+limits.caseTimeout().toNanos()*cases.size()+Duration.ofSeconds(10).toNanos();
			long started=System.nanoTime(); Path response=job.resolve("response.json");
			while(!Files.exists(response,LinkOption.NOFOLLOW_LINKS)) {
				if(System.nanoTime()-started>maximum) throw new Unavailable();
				Thread.sleep(25);
			}
			if(!Files.isRegularFile(response,LinkOption.NOFOLLOW_LINKS)||Files.size(response)>2_000_000) throw new Unavailable();
			var body=json.readTree(Files.readString(response));
			if(body.path("unavailable").asBoolean(false)) throw new Unavailable();
			List<Result> results=json.readValue(body.path("results").toString(),json.getTypeFactory().constructCollectionType(List.class,Result.class));
			if(results.size()!=cases.size()||results.stream().anyMatch(r->r.error()==null||r.output()==null
					||r.output().getBytes(StandardCharsets.UTF_8).length>limits.outputBytes()||r.ms()<0)) throw new Unavailable();
			return List.copyOf(results);
		}
		catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new Unavailable(); }
		catch(IOException|IllegalArgumentException e) { throw new Unavailable(); }
		catch(RuntimeException e) { throw new Unavailable(); }
		finally {
			if(job!=null) {
				try { Files.writeString(job.resolve("cancel"),""); } catch(IOException ignored) { }
				for(String file:List.of("request.tmp","request.json","response.tmp","response.json","cancel")) try { Files.deleteIfExists(job.resolve(file)); } catch(IOException ignored) { }
				try { Files.deleteIfExists(job); } catch(IOException ignored) { }
			}
			if(acquired) slots.release();
		}
	}
}
