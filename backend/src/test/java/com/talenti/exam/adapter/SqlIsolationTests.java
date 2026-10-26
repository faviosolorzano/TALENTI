package com.talenti.exam.adapter;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;
import com.talenti.exam.config.ExamProperties;
import com.talenti.exam.domain.ExamQuestion.*;
import com.talenti.exam.port.CodeExecutionPort;
import tools.jackson.databind.json.JsonMapper;

/** Caja SQL real y separada; nunca se conecta al PostgreSQL de Talenti. */
class SqlIsolationTests {
	private static PostgreSQLContainer database;
	private static SqlCodeExecution execution;
	private static final JsonMapper JSON=JsonMapper.builder().build();
	private static final String OWNER="owner-solo-pruebas",READER="reader-solo-pruebas";
	private static final CodeExecutionPort.Limits LIMITS=new CodeExecutionPort.Limits(Duration.ofSeconds(3),Duration.ofSeconds(10),256,65536,10000,64);
	@BeforeAll static void startDatabase() {
		var image=new ImageFromDockerfile("talenti-s14-sql-tests-"+UUID.randomUUID(),true)
				.withFileFromPath("Dockerfile",Path.of("..","exam-sql","Dockerfile"))
				.withFileFromPath("init-exam.sh",Path.of("..","exam-sql","init-exam.sh"));
		database=new PostgreSQLContainer(DockerImageName.parse(image.get()).asCompatibleSubstituteFor("postgres"))
				.withDatabaseName("exam").withUsername("exam_bootstrap").withPassword("bootstrap-solo-pruebas")
				.withEnv("EXAM_SQL_OWNER_PASSWORD",OWNER).withEnv("EXAM_SQL_READER_PASSWORD",READER);
		database.start();
		execution=new SqlCodeExecution(new ExamProperties.Sql(database.getJdbcUrl(),"exam_owner",OWNER,"exam_reader",READER,Duration.ofSeconds(2),1000),JSON);
	}
	@AfterAll static void stopDatabase() { if(database!=null) database.stop(); }
	private static Case fixture(String id,String value) {
		return new Case(id,true,"","",null,new SqlTable(List.of("n"),List.of(List.of(value)),List.of("int4")),
				List.of(new SqlFixture("numbers",List.of(new SqlColumn("n","integer")),List.of(List.of(value)))));
	}
	private static CodeExecutionPort.Result run(String code,Case data) { return execution.run(Language.SQL,code,List.of(data),LIMITS).getFirst(); }
	@Test void t014_30_selectHasReadOnlyRoleAndCorrectTypedResult() {
		var result=run("SELECT n FROM numbers",fixture("one","7"));
		assertThat(result.completed()).isTrue();assertThat(result.table().rows()).containsExactly(List.of("7"));
		assertThat(result.table().types()).containsExactly("int4");
		assertThat(run("SELECT current_setting('transaction_read_only') AS read_only",fixture("one","7")).table().rows()).containsExactly(List.of("on"));
	}
	@Test void t014_31_writesMultipleStatementsAndServerFilesAreBlocked() {
		for(String code:List.of("SELECT 1; DROP TABLE numbers","INSERT INTO numbers VALUES (3)","DROP TABLE numbers",
				"WITH x AS (DELETE FROM numbers RETURNING *) SELECT * FROM x","SELECT pg_read_file('/etc/passwd')"))
			assertThat(run(code,fixture("one","7")).completed()).as("ataque bloqueado").isFalse();
		assertThat(run("SELECT n FROM numbers",fixture("one","7")).table().rows()).containsExactly(List.of("7"));
	}
	@Test void t014_31_sleepAndDisablingTheTimeoutAreBounded() {
		long start=System.nanoTime();
		assertThat(run("SELECT pg_sleep(10)",fixture("one","7")).error()).isEqualTo(CodeExecutionPort.Error.TIMEOUT);
		assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(5));
		assertThat(run("SELECT set_config('statement_timeout','0',false)",fixture("one","7")).completed()).isFalse();
	}
	@Test void t014_30_readerCannotReadAnotherCaseOrTalentiTables() throws Exception {
		Case first=fixture("private","17");run("SELECT n FROM numbers",first);
		String schema=null;
		try(Connection admin=DriverManager.getConnection(database.getJdbcUrl(),"exam_bootstrap",database.getPassword());
			Statement s=admin.createStatement();ResultSet r=s.executeQuery("SELECT nspname FROM pg_namespace WHERE nspname LIKE 'exam_case_%' ORDER BY nspname")) {
			while(r.next()) {
				String candidate=r.getString(1);
				try(Statement read=admin.createStatement();ResultSet data=read.executeQuery("SELECT n FROM "+candidate+".numbers")) {
					if(data.next()&&data.getInt(1)==17) { schema=candidate;break; }
				}
			}
		}
		assertThat(schema).isNotNull();
		assertThat(run("SELECT n FROM "+schema+".numbers",fixture("other","23")).completed()).isFalse();
		assertThat(run("SELECT * FROM app_user",fixture("other","23")).completed()).isFalse();
	}
	@Test void t014_30_rowsAndOutputAreBounded() {
		assertThat(run("SELECT generate_series(1,1001)",fixture("one","7")).error()).isEqualTo(CodeExecutionPort.Error.OUTPUT_LIMIT);
		assertThat(run("SELECT repeat('x',100000)",fixture("one","7")).error()).isEqualTo(CodeExecutionPort.Error.OUTPUT_LIMIT);
	}
	@Test void t014_04_bankSqlReferencesRunTwiceAndStartersFail() throws Exception {
		ExamBank bank;
		try(var in=SqlIsolationTests.class.getResourceAsStream("/exam/bank-v1.json")) {bank=new ExamBank(JSON.readTree(in),JSON);}
		var plan=com.talenti.exam.domain.Blueprint.create(List.of(new com.talenti.exam.domain.Blueprint.Technology("sql","SQL",true,2)),Map.of("sql",Language.SQL),new java.math.BigDecimal("6.25"),java.math.BigDecimal.TEN,new java.math.BigDecimal("15"));
		var report=new com.talenti.exam.domain.ExamVerifier(execution,LIMITS,2,3).verify(plan,bank.version(plan,"A"));
		assertThat(report.accepted()).as(report.failures().toString()).isTrue();
	}
}
