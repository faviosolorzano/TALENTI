package com.talenti.exam.adapter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import com.talenti.exam.config.ExamProperties;
import com.talenti.exam.domain.*;
import com.talenti.exam.domain.ExamQuestion.*;
import com.talenti.exam.port.CodeExecutionPort;
import tools.jackson.databind.json.JsonMapper;

/** Base separada sin tablas de Talenti; el lector tiene acceso únicamente al caso actualmente ejecutado. */
public final class SqlCodeExecution implements CodeExecutionPort {
	private static final long LOCK=0x54414c4558514cL;
	private final ExamProperties.Sql properties;
	private final JsonMapper json;
	public SqlCodeExecution(ExamProperties.Sql properties,JsonMapper json) { this.properties=properties; this.json=json; }
	@Override public List<Result> run(Language language,String code,List<Case> cases,Limits limits) {
		if(language!=Language.SQL||code==null||code.getBytes(StandardCharsets.UTF_8).length>limits.codeBytes()) throw new Unavailable();
		if(!SqlReadOnly.accepts(code)) return cases.stream().map(c->new Result(false,"",Error.SYNTAX,0,null)).toList();
		List<Result> results=new ArrayList<>();
		try(Connection owner=DriverManager.getConnection(properties.url(),properties.owner(),properties.ownerPassword())) {
			lock(owner);
			try {
				for(Case c:cases) {
					if(!SqlFixtures.valid(c.tables())) throw new Unavailable();
					String schema=schema(c.tables());
					mount(owner,schema,c.tables());
					revokeAllCases(owner);
					String reader=quoteRole(properties.reader());
					try(Statement grant=owner.createStatement()) {
						grant.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+reader);
						grant.execute("GRANT SELECT ON ALL TABLES IN SCHEMA "+schema+" TO "+reader);
					}
					try { results.add(query(schema,code,limits)); }
					finally { revokeAllCases(owner); }
				}
			}
			finally { try(PreparedStatement unlock=owner.prepareStatement("SELECT pg_advisory_unlock(?)")) { unlock.setLong(1,LOCK); unlock.execute(); } }
			return List.copyOf(results);
		}
		catch(SQLException e) { throw new Unavailable(); }
	}
	private void lock(Connection connection) throws SQLException {
		long started=System.nanoTime();
		while(true) {
			try(PreparedStatement lock=connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
				lock.setLong(1,LOCK); try(ResultSet r=lock.executeQuery()) { r.next(); if(r.getBoolean(1)) return; }
			}
			if(System.nanoTime()-started>Duration.ofSeconds(15).toNanos()) throw new Unavailable();
			try { Thread.sleep(25); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new Unavailable(); }
		}
	}
	private String schema(List<SqlFixture> tables) {
		try { return "exam_case_"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(tables))).substring(0,24); }
		catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
	}
	private static String quoteRole(String role) { if(!role.matches("[a-z][a-z0-9_]{0,39}")) throw new IllegalArgumentException("Rol SQL inválido"); return '"'+role+'"'; }
	private void mount(Connection owner,String schema,List<SqlFixture> tables) throws SQLException {
		try(PreparedStatement exists=owner.prepareStatement("SELECT count(*) FROM pg_namespace WHERE nspname=?")) {
			exists.setString(1,schema); try(ResultSet r=exists.executeQuery()) { r.next(); if(r.getInt(1)>0) return; }
		}
		owner.setAutoCommit(false);
		try(Statement create=owner.createStatement()) {
			create.execute("CREATE SCHEMA "+schema);
			for(SqlFixture table:tables) {
				String columns=table.columns().stream().map(c->'"'+c.name()+"\" "+c.type()).collect(java.util.stream.Collectors.joining(","));
				create.execute("CREATE TABLE "+schema+'.'+table.name()+" ("+columns+")");
				String values=table.columns().stream().map(c->"CAST(? AS "+c.type()+")").collect(java.util.stream.Collectors.joining(","));
				try(PreparedStatement insert=owner.prepareStatement("INSERT INTO "+schema+'.'+table.name()+" VALUES ("+values+")")) {
					for(var row:table.rows()) { for(int i=0;i<row.size();i++) insert.setString(i+1,row.get(i)); insert.addBatch(); }
					insert.executeBatch();
				}
			}
			owner.commit();
		}
		catch(SQLException e) { owner.rollback(); throw e; }
		finally { owner.setAutoCommit(true); }
	}
	private void revokeAllCases(Connection owner) throws SQLException {
		List<String> schemas=new ArrayList<>();
		try(PreparedStatement query=owner.prepareStatement("SELECT nspname FROM pg_namespace WHERE nspname ~ '^exam_case_[0-9a-f]{24}$' AND nspowner=current_user::regrole");ResultSet r=query.executeQuery()) {
			while(r.next()) schemas.add(r.getString(1));
		}
		try(Statement revoke=owner.createStatement()) {
			for(String schema:schemas) {
				revoke.execute("REVOKE ALL ON ALL TABLES IN SCHEMA "+schema+" FROM "+quoteRole(properties.reader()));
				revoke.execute("REVOKE ALL ON SCHEMA "+schema+" FROM "+quoteRole(properties.reader()));
			}
		}
	}
	private Result query(String schema,String code,Limits limits) {
		long started=System.nanoTime();
		try(Connection reader=DriverManager.getConnection(properties.url(),properties.reader(),properties.readerPassword())) {
			reader.setAutoCommit(false); reader.setReadOnly(true);
			try {
				try(Statement config=reader.createStatement()) {
					config.execute("SET LOCAL search_path TO "+schema+",pg_catalog");
					config.execute("SET LOCAL statement_timeout='"+properties.statementTimeout().toMillis()+"ms'");
				}
				try(PreparedStatement statement=reader.prepareStatement(code)) {
					statement.setMaxRows(properties.maxRows()+1); statement.setFetchSize(100);
					statement.setQueryTimeout((int)Math.max(1,(properties.statementTimeout().toMillis()+999)/1000));
					try(ResultSet rs=statement.executeQuery()) {
						ResultSetMetaData meta=rs.getMetaData(); List<String> columns=new ArrayList<>(),types=new ArrayList<>();
						if(meta.getColumnCount()>100) return result(false,Error.OUTPUT_LIMIT,started,null);
						for(int i=1;i<=meta.getColumnCount();i++) { columns.add(meta.getColumnLabel(i)); types.add(meta.getColumnTypeName(i)); }
						List<List<String>> rows=new ArrayList<>(); int bytes=0;
						while(rs.next()) {
							if(rows.size()>=properties.maxRows()) return result(false,Error.OUTPUT_LIMIT,started,null);
							List<String> row=new ArrayList<>();
							for(int i=1;i<=columns.size();i++) {
								String value=rs.getString(i);
								if(value!=null&&types.get(i-1).equals("timestamptz")) value=rs.getObject(i,OffsetDateTime.class).toInstant().toString();
								else if(value!=null&&types.get(i-1).equals("timestamp")) value=rs.getObject(i,LocalDateTime.class).toInstant(ZoneOffset.UTC).toString();
								bytes+=value==null?4:value.getBytes(StandardCharsets.UTF_8).length;
								if(bytes>limits.outputBytes()) return result(false,Error.OUTPUT_LIMIT,started,null);
								row.add(value);
							}
							rows.add(row);
						}
						return result(true,Error.NONE,started,new SqlTable(columns,rows,types));
					}
				}
			}
			finally { reader.rollback(); }
		}
		catch(SQLException e) {
			String state=Objects.toString(e.getSQLState(),"");
			if(state.startsWith("08")||state.startsWith("28")||state.startsWith("57P")) throw new Unavailable();
			return result(false,state.equals("57014")?Error.TIMEOUT:state.equals("42601")?Error.SYNTAX:Error.RUNTIME,started,null);
		}
	}
	private static Result result(boolean completed,Error error,long started,SqlTable table) { return new Result(completed,"",error,(System.nanoTime()-started)/1_000_000,table); }
}
