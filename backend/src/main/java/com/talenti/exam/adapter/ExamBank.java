package com.talenti.exam.adapter;

import java.util.*;
import com.talenti.exam.domain.*;
import com.talenti.exam.domain.ExamQuestion.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Banco versionado; aun este contenido debe pasar el verificador y ejecutar sus referencias. */
public final class ExamBank {
	private final JsonNode bank;
	private final JsonMapper json;
	public ExamBank(JsonNode bank,JsonMapper json) { this.bank=bank; this.json=json; }
	public String version() { return bank.path("version").asString(); }
	public List<ExamQuestion> version(Blueprint blueprint,String label) {
		int variant=label.charAt(0)-'A';
		List<ExamQuestion> questions=new ArrayList<>();
		for(var slot:blueprint.slots()) {
			int index=(variant+slot.position()-1)%4;
			String prompt; List<Option> options=List.of(); List<Criterion> rubric=List.of(); List<String> keyPoints=List.of();
			String initial=null; List<String> references=List.of(); List<Case> cases=List.of();
			if(slot.type()==Type.CHOICE) {
				prompt=bank.path("choicePrompts").get(index).asString().replace("{technology}",slot.skillName());
				List<Option> items=new ArrayList<>(); items.add(new Option("correct",bank.path("choiceCorrect").get(index).asString()));
				int d=0; for(JsonNode text:bank.path("distractors").get(index)) items.add(new Option("d"+d++,text.asString()));
				options=List.copyOf(items);
			}
			else if(slot.type()==Type.OPEN) {
				prompt=bank.path("openPrompts").get((variant+slot.position())%3).asString().replace("{technology}",slot.skillName());
				rubric=json.readValue(bank.path("criteria").toString(),json.getTypeFactory().constructCollectionType(List.class,Criterion.class));
				keyPoints=bank.path("keyPoints").valueStream().map(JsonNode::asString).toList();
			}
			else {
				int offset=variant+1;
				prompt=slot.language()==Language.SQL ? "En el contexto de "+slot.skillName()+", consulta la tabla numbers(n integer). Devuelve una columna total con la suma de n más "+offset+". Devuelve una sola fila."
						: "En el contexto de "+slot.skillName()+", lee dos enteros de la entrada estándar, suma ambos y añade "+offset+". Imprime solo el resultado. En Java usa public class Main.";
				prompt += slot.kind()==CodeKind.FIX ? " Corrige el código inicial." : slot.kind()==CodeKind.COMPLETE ? " Completa el código inicial." : " Escribe la solución.";
				references=List.of(program(slot.language(),offset,false));
				initial=slot.kind()==CodeKind.WRITE ? "" : program(slot.language(),offset,true);
				List<Case> data=new ArrayList<>();
				int[][] pairs={{1,2},{4,6},{-2,8},{0,0},{30,-9}};
				for(int i=0;i<pairs.length;i++) {
					int a=pairs[i][0],b=pairs[i][1]; String expected=Integer.toString(a+b+offset);
					data.add(new Case("case-"+i,i<2,a+" "+b,expected,
							slot.language()==Language.SQL?"numbers(n integer)":null,
							slot.language()==Language.SQL?new SqlTable(List.of("total"),List.of(List.of(expected)),List.of("int8")):null,
							slot.language()==Language.SQL?List.of(new SqlFixture("numbers",List.of(new SqlColumn("n","integer")),List.of(List.of(Integer.toString(a)),List.of(Integer.toString(b))))):List.of()));
				}
				cases=List.copyOf(data);
			}
			questions.add(new ExamQuestion(UUID.randomUUID(),
					slot.position(),slot.type(),slot.skillSlug(),slot.difficulty(),slot.points(),prompt,options,
					slot.type()==Type.CHOICE?"correct":null,rubric,keyPoints,slot.language(),slot.kind(),initial,references,cases,false,Source.BANK));
		}
		return List.copyOf(questions);
	}
	private static String program(Language language,int offset,boolean broken) {
		String op=broken?"-":"+";
		return switch(language) {
			case JAVA -> (broken?"/* EXAM_INITIAL */\n":"")+"import java.util.Scanner; public class Main { public static void main(String[] args) { Scanner s=new Scanner(System.in); long a=s.nextLong(), b=s.nextLong(); System.out.println(a "+op+" b + "+offset+"); } }";
			case JAVASCRIPT -> (broken?"// EXAM_INITIAL\n":"")+"const fs=require('fs'); const [a,b]=fs.readFileSync(0,'utf8').trim().split(/\\s+/).map(Number); console.log(a "+op+" b + "+offset+");";
			case PYTHON -> (broken?"# EXAM_INITIAL\n":"")+"import sys\na,b=map(int,sys.stdin.read().split())\nprint(a "+op+" b + "+offset+")\n";
			case SQL -> (broken?"-- EXAM_INITIAL\n":"")+"SELECT "+(broken?"MIN":"SUM")+"(n) + "+offset+" AS total FROM numbers";
		};
	}
}
