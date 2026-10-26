package com.talenti.exam.adapter;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;
import com.github.dockerjava.api.model.Capability;
import com.talenti.exam.domain.ExamQuestion.Language;
import com.talenti.exam.port.CodeExecutionPort;
import tools.jackson.databind.json.JsonMapper;

/** El protocolo JSON cruza la cola del contenedor real; no requiere montar carpetas Windows con permisos distintos. */
class RunnerIsolationTests {
	private static GenericContainer<?> runner;
	private static final JsonMapper JSON=JsonMapper.builder().build();
	private static final CodeExecutionPort.Limits LIMITS=new CodeExecutionPort.Limits(Duration.ofSeconds(3),Duration.ofSeconds(10),256,65536,10000,64);
	@BeforeAll static void startRunner() {
		var image=new ImageFromDockerfile("talenti-s14-runner-tests-"+UUID.randomUUID(),true)
				.withFileFromPath("Dockerfile",Path.of("..","code-runner","Dockerfile"))
				.withFileFromPath("runner.py",Path.of("..","code-runner","runner.py"));
		String built=System.getProperty("talenti.test.runner-image");
		runner=(built==null?new GenericContainer<>(image):new GenericContainer<>(DockerImageName.parse(built))).withCreateContainerCmdModifier(cmd->cmd.getHostConfig()
				.withNetworkMode("none").withReadonlyRootfs(true).withCapDrop(Capability.ALL)
				.withCapAdd(Capability.SETUID,Capability.SETGID,Capability.KILL).withSecurityOpts(List.of("no-new-privileges"))
				.withMemory(768L*1024*1024).withPidsLimit(160L).withNanoCPUs(2_000_000_000L)
				.withTmpFs(Map.of("/tmp","rw,exec,nosuid,nodev,size=512m,mode=1777","/jobs","rw,noexec,nosuid,nodev,size=32m,mode=0700,uid=10001,gid=10001")))
				.waitingFor(Wait.forLogMessage(".*exam-runner-ready uid=10001.*",1).withStartupTimeout(Duration.ofSeconds(60)));
		runner.start();
	}
	@AfterAll static void stopRunner() { if(runner!=null) runner.stop(); }
	private static CodeExecutionPort.Result run(Language language,String code,String input) throws Exception {
		return runCases(language,code,List.of(input)).getFirst();
	}
	private static List<CodeExecutionPort.Result> runCases(Language language,String code,List<String> inputs) throws Exception {
		String id=UUID.randomUUID().toString();
		String payload=JSON.writeValueAsString(Map.of("language",language.name(),"code",code,"inputs",inputs,"limits",LIMITS));
		String encoded=Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
		String make="import os,pathlib,sys,base64;os.setgroups([]);os.setresgid(10001,10001,10001);os.setresuid(10001,10001,10001);os.umask(0o077);p=pathlib.Path('/jobs')/sys.argv[1];p.mkdir();(p/'request.json').write_bytes(base64.b64decode(sys.argv[2]))";
		assertThat(runner.execInContainer("python","-c",make,id,encoded).getExitCode()).isZero();
		String read="import os,pathlib,sys;os.setgroups([]);os.setresgid(10001,10001,10001);os.setresuid(10001,10001,10001);p=pathlib.Path('/jobs')/sys.argv[1]/'response.json';print(p.read_text() if p.exists() else '')";
		long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();String response="";
		while(response.isBlank()) {
			if(System.nanoTime()>deadline) throw new AssertionError("No llegó la respuesta de la caja");
			response=runner.execInContainer("python","-c",read,id).getStdout();
			if(response.isBlank()) Thread.sleep(50);
		}
		var root=JSON.readTree(response);
		assertThat(root.path("unavailable").asBoolean(false)).as("supervisor disponible").isFalse();
		List<CodeExecutionPort.Result> result=JSON.readValue(root.path("results").toString(),JSON.getTypeFactory().constructCollectionType(List.class,CodeExecutionPort.Result.class));
		runner.execInContainer("python","-c","import os,shutil,sys;os.setgroups([]);os.setresgid(10001,10001,10001);os.setresuid(10001,10001,10001);shutil.rmtree('/jobs/'+sys.argv[1])",id);
		return result;
	}
	@Test void t014_04_bankReferencesAndBrokenStartersAreVerifiedByRealExecution() throws Exception {
		ExamBank bank;
		try(var in=RunnerIsolationTests.class.getResourceAsStream("/exam/bank-v1.json")) { bank=new ExamBank(JSON.readTree(in),JSON); }
		for(Language language:List.of(Language.JAVA,Language.JAVASCRIPT,Language.PYTHON)) {
			var plan=com.talenti.exam.domain.Blueprint.create(List.of(new com.talenti.exam.domain.Blueprint.Technology("test","Tecnología",true,2)),Map.of("test",language),new java.math.BigDecimal("6.25"),java.math.BigDecimal.TEN,new java.math.BigDecimal("15"));
			CodeExecutionPort port=(lang,code,cases,limits)->{try {return runCases(lang,code,cases.stream().map(com.talenti.exam.domain.ExamQuestion.Case::input).toList());}catch(Exception e){throw new CodeExecutionPort.Unavailable();}};
			var report=new com.talenti.exam.domain.ExamVerifier(port,LIMITS,2,3).verify(plan,bank.version(plan,"A"));
			assertThat(report.accepted()).as(language+" "+report.failures()).isTrue();
		}
	}
	@ParameterizedTest @EnumSource(value=Language.class,names={"JAVA","JAVASCRIPT","PYTHON"})
	void t014_28_runsAllThreeLanguages(Language language) throws Exception {
		String code=switch(language) {
			case JAVA->"public class Main {public static void main(String[] args){java.util.Scanner s=new java.util.Scanner(System.in);System.out.println(s.nextInt()+s.nextInt());}}";
			case JAVASCRIPT->"const[a,b]=require('fs').readFileSync(0,'utf8').trim().split(/\\s+/).map(Number);console.log(a+b);";
			case PYTHON->"a,b=map(int,input().split());print(a+b)";
			default->throw new IllegalArgumentException();
		};
		var result=run(language,code,"2 3");assertThat(result.completed()).as(language.toString()+" "+result.error()).isTrue();assertThat(result.output().strip()).isEqualTo("5");
	}
	@Test void t014_29_programHasNoCapabilitiesAndCannotBecomeSupervisor() throws Exception {
		assertThat(runner.execInContainer("python","-c","print([s for s in open('/proc/1/status') if s.startswith('Uid:')][0])").getStdout()).contains("10001");
		String code="import os\nprint(os.getuid())\nprint([s for s in open('/proc/self/status') if s.startswith('CapEff')][0].strip())\ntry: os.setuid(10001)\nexcept PermissionError: print('DENIED')";
		var result=run(Language.PYTHON,code,"");assertThat(result.completed()).isTrue();
		assertThat(result.output()).contains("2000","0000000000000000","DENIED");
	}
	@Test void t014_31_networkIncludingLoopbackIsDenied() throws Exception {
		var result=run(Language.PYTHON,"import socket\ntry: socket.socket()\nexcept OSError as e: print(e.errno)","");
		assertThat(result.completed()).isTrue();assertThat(result.output().strip()).isEqualTo("1");
	}
	@Test void t014_31_jobsAndRootFilesystemAreNotAccessible() throws Exception {
		assertThat(run(Language.PYTHON,"import os\nprint(os.listdir('/jobs'))","").completed()).isFalse();
		assertThat(run(Language.PYTHON,"open('/etc/exam-probe','w').write('x')","").completed()).isFalse();
	}
	@Test void t014_31_infiniteLoopAndOutputAreBounded() throws Exception {
		long started=System.nanoTime();
		assertThat(run(Language.PYTHON,"while True: pass","").error()).isEqualTo(CodeExecutionPort.Error.TIMEOUT);
		assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(10));
		var output=run(Language.PYTHON,"while True: print('x'*1000)","");
		assertThat(output.error()).isEqualTo(CodeExecutionPort.Error.OUTPUT_LIMIT);assertThat(output.output().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(65536);
	}
	@Test void t014_31_memoryAndEscapedProcessesAreCleanedBeforeNextJob() throws Exception {
		assertThat(run(Language.PYTHON,"a=[]\nwhile True: a.append(bytearray(10*1024*1024))","").error()).isEqualTo(CodeExecutionPort.Error.MEMORY_LIMIT);
		assertThat(run(Language.PYTHON,"import os,time\nfor i in range(100):\n try:\n  if os.fork()==0: os.setsid();time.sleep(60);os._exit(0)\n except OSError: pass\nwhile True: pass","").completed()).isFalse();
		assertThat(run(Language.PYTHON,"print('READY')","").output().strip()).isEqualTo("READY");
	}
}
