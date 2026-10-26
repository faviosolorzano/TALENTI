package com.talenti.exam.adapter;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

/** Transporte sustituible por una función simulada en todas las pruebas de contrato. */
@FunctionalInterface
public interface ExamAiTransport {
	String post(String json,Duration timeout);

	static ExamAiTransport http(String baseUrl,String apiKey) {
		if(apiKey==null||apiKey.isBlank()) throw new IllegalStateException("Examen con IA encendido pero falta OPENAI_API_KEY");
		HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
		URI endpoint=URI.create(baseUrl.replaceAll("/+$","")+"/responses");
		return (body,timeout)->{
			var subscriber=new LimitedBody();
			var request=HttpRequest.newBuilder(endpoint).timeout(timeout).header("Authorization","Bearer "+apiKey)
					.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
			var pending=client.sendAsync(request,info->subscriber);
			try {
				var response=pending.get(timeout.toNanos(),TimeUnit.NANOSECONDS);
				if(response.statusCode()!=200) throw new IllegalStateException("exam-provider-unavailable");
				return new String(response.body(),StandardCharsets.UTF_8);
			}
			catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("exam-provider-interrupted"); }
			catch(ExecutionException|TimeoutException e) { throw new IllegalStateException("exam-provider-unavailable"); }
			finally { subscriber.cancel(); pending.cancel(true); }
		};
	}
	final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
		private final CompletableFuture<byte[]> body=new CompletableFuture<>();
		private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
		private volatile java.util.concurrent.Flow.Subscription subscription;
		private volatile boolean cancelled;
		@Override public CompletionStage<byte[]> getBody() { return body; }
		@Override public void onSubscribe(java.util.concurrent.Flow.Subscription next) { subscription=next; if(cancelled) next.cancel(); else next.request(1); }
		@Override public void onNext(List<ByteBuffer> buffers) {
			for(var b:buffers) {
				if(b.remaining()>2_000_000-bytes.size()) { body.completeExceptionally(new IllegalStateException("exam-provider-body-limit")); cancel(); return; }
				byte[] chunk=new byte[b.remaining()]; b.get(chunk); bytes.writeBytes(chunk);
			}
			subscription.request(1);
		}
		@Override public void onError(Throwable e) { body.completeExceptionally(e); }
		@Override public void onComplete() { body.complete(bytes.toByteArray()); }
		void cancel() { cancelled=true; var current=subscription; if(current!=null) current.cancel(); }
	}
}
