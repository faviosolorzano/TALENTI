package com.talenti.exam.adapter;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;

/** Esquema cerrado de los records del puerto: todos los campos requeridos y ausencia de claves desconocidas. */
final class ExamSchemas {
	private ExamSchemas() { }
	static Map<String,Object> object(Map<String,Object> fields) {
		return Map.of("type","object","properties",fields,"required",List.copyOf(fields.keySet()),"additionalProperties",false);
	}
	static Object array(Object item) { return Map.of("type","array","items",item); }
	static Object grading(List<com.talenti.exam.domain.ExamQuestion.Criterion> rubric) {
		return object(Map.of("criteria",Map.of("type","array","items",object(Map.of(
				"criterion",Map.of("type","string","enum",rubric.stream().map(com.talenti.exam.domain.ExamQuestion.Criterion::id).toList()),
				"level",Map.of("type","integer","enum",List.of(1,2,3,4)),
				"comment",Map.of("type",List.of("string","null")),
				"quotes",array(Map.of("type","string")))))));
	}
	static Object schema(Type type) {
		if(type instanceof ParameterizedType p) {
			if(p.getRawType()==List.class) return array(schema(p.getActualTypeArguments()[0]));
			if(p.getRawType()==Map.class) return object(Map.of("1",Map.of("type","string"),"2",Map.of("type","string"),"3",Map.of("type","string"),"4",Map.of("type","string")));
		}
		Class<?> c=(Class<?>)type;
		if(c==UUID.class) return Map.of("type","null"); // los ids los crea el backend
		if(c==String.class) return Map.of("type",List.of("string","null"));
		if(c==int.class||c==long.class||c==Integer.class) return Map.of("type","integer");
		if(c==boolean.class) return Map.of("type","boolean");
		if(c==BigDecimal.class) return Map.of("type","number");
		if(c.isEnum()) return Map.of("anyOf",List.of(Map.of("type","string","enum",Arrays.stream(c.getEnumConstants()).map(Object::toString).toList()),Map.of("type","null")));
		if(c.isRecord()) {
			Map<String,Object> fields=new LinkedHashMap<>();
			for(var part:c.getRecordComponents()) fields.put(part.getName(),schema(part.getGenericType()));
			return Map.of("anyOf",List.of(object(fields),Map.of("type","null")));
		}
		throw new IllegalArgumentException("Tipo de esquema no admitido");
	}
}
