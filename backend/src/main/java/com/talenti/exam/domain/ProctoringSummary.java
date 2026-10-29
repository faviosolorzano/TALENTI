package com.talenti.exam.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import com.talenti.exam.ExamResults;
import com.talenti.exam.domain.AttemptRules.EventKind;

/**
 * Resumen de hechos para la futura empresa (014-57): por tipo, cantidad y duración total; episodios con inicio, fin,
 * duración y foto asociada; y la lista de fotos. Es una cuenta de hechos: no produce puntaje, sospecha ni orden entre
 * personas (principios 1 y 6). Los episodios del navegador se unen por {@code episodeId}; pérdida y recuperación de
 * cámara, de pantalla y de conexión se unen por orden de llegada (hora del servidor).
 */
public final class ProctoringSummary {
	private ProctoringSummary() { }
	public record Fact(String kind,UUID clientEventId,Instant occurredAt,Map<String,Object> data) { }
	public record Photo(UUID id,Instant takenAt,String reason,UUID eventClientId) { }
	public record Built(List<ExamResults.KindTotal> totals,List<ExamResults.Episode> episodes,List<ExamResults.Snapshot> snapshots) { }
	private static final Map<String,String> PAIRS=Map.of("CAMERA_LOST","CAMERA_RESTORED","SCREEN_LEFT","SCREEN_RETURNED",
			"CONNECTION_LOST","CONNECTION_RESTORED");
	private static final Set<String> CLIENT_KINDS=new LinkedHashSet<>();
	static { for(var k:EventKind.values()) CLIENT_KINDS.add(k.name()); }
	private static final class Open {
		final String kind; final UUID episodeId; Instant start; Instant end; Long since; Long duration; UUID startClient;
		Open(String kind,UUID episodeId) { this.kind=kind; this.episodeId=episodeId; }
	}

	public static Built build(List<Fact> facts,List<Photo> photos) {
		Map<UUID,UUID> photoByEvent=new HashMap<>();
		for(var p:photos) if(p.eventClientId()!=null) photoByEvent.putIfAbsent(p.eventClientId(),p.id());
		Map<String,int[]> counts=new LinkedHashMap<>();
		Map<String,long[]> durations=new HashMap<>();
		Map<UUID,Open> byEpisode=new LinkedHashMap<>();
		Map<String,Open> pairOpen=new HashMap<>();
		List<Open> episodes=new ArrayList<>();
		var ordered=facts.stream().sorted(Comparator.comparing(Fact::occurredAt)).toList();
		for(var f:ordered) {
			if(!CLIENT_KINDS.contains(f.kind())) continue;
			Map<String,Object> data=f.data()==null?Map.of():f.data();
			Object phase=data.get("phase"); Object episode=data.get("episodeId");
			if(phase!=null&&episode!=null) {
				UUID id=UUID.fromString(episode.toString());
				Open e=byEpisode.computeIfAbsent(id,x->{ var o=new Open(f.kind(),x); episodes.add(o); return o; });
				if("START".equals(phase)) {
					counts.computeIfAbsent(f.kind(),k->new int[1])[0]++;
					e.start=f.occurredAt(); e.since=number(data.get("sinceMs")); e.startClient=f.clientEventId();
				}
				else {
					e.end=f.occurredAt(); e.duration=number(data.get("durationMs"));
					if(e.duration!=null) durations.computeIfAbsent(f.kind(),k->new long[1])[0]+=e.duration;
				}
				continue;
			}
			counts.computeIfAbsent(f.kind(),k->new int[1])[0]++;
			if(PAIRS.containsKey(f.kind())) {
				if(!pairOpen.containsKey(f.kind())) {
					var o=new Open(f.kind(),null); o.start=f.occurredAt(); o.startClient=f.clientEventId();
					pairOpen.put(f.kind(),o); episodes.add(o);
				}
			}
			else for(var pair:PAIRS.entrySet()) if(pair.getValue().equals(f.kind())) {
				var o=pairOpen.remove(pair.getKey());
				if(o!=null) {
					o.end=f.occurredAt(); o.duration=Duration.between(o.start,o.end).toMillis();
					durations.computeIfAbsent(o.kind,k->new long[1])[0]+=o.duration;
				}
			}
		}
		List<ExamResults.KindTotal> totals=new ArrayList<>();
		counts.forEach((kind,c)->totals.add(new ExamResults.KindTotal(kind,c[0],durations.getOrDefault(kind,new long[1])[0])));
		List<ExamResults.Episode> list=new ArrayList<>();
		for(var e:episodes) {
			Instant start=e.start!=null?e.start:e.end!=null&&e.duration!=null?e.end.minusMillis(e.duration):e.end;
			UUID photo=e.startClient==null?null:photoByEvent.get(e.startClient);
			if(photo==null&&e.episodeId!=null) photo=photoByEvent.get(e.episodeId);
			list.add(new ExamResults.Episode(e.kind,e.episodeId,start,e.end,e.since,e.duration,photo));
		}
		list.sort(Comparator.comparing(ExamResults.Episode::startedAt,Comparator.nullsLast(Comparator.naturalOrder())));
		return new Built(List.copyOf(totals),List.copyOf(list),
				photos.stream().map(p->new ExamResults.Snapshot(p.id(),p.takenAt(),p.reason(),p.eventClientId())).toList());
	}
	private static Long number(Object value) { return value instanceof Number n?n.longValue():null; }
}
