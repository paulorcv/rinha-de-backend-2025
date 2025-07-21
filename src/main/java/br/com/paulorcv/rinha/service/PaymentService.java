package br.com.paulorcv.rinha.service;

import br.com.paulorcv.rinha.dto.PaymentRequest;
import br.com.paulorcv.rinha.dto.PaymentsSummaryResponse;
import br.com.paulorcv.rinha.dto.ServiceHealthResponse;
import br.com.paulorcv.rinha.model.Payment;
import br.com.paulorcv.rinha.repository.PaymentRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

	private final WebClient.Builder webClientBuilder;
	private final PaymentRepository repo;
	private final DatabaseClient db;

	@Value("${payment.processor.main.healthcheck-url}")
	private String mainHealthUrl;
	@Value("${payment.processor.fallback.healthcheck-url}")
	private String fallBackHealthUrl;

	@Value("${payment.processor.main.pay-url}")
	private String mainPayUrl;
	@Value("${payment.processor.fallback.pay-url}")
	private String fallbackPayUrl;

	private final AtomicReference<ServiceHealthResponse> mainHealthCache = new AtomicReference<>();
	private final AtomicReference<ServiceHealthResponse> fallbackHealthCache = new AtomicReference<>();
	private final Sinks.Many<PaymentRequest> paymentQueue = Sinks.many().unicast().onBackpressureBuffer();


	@PostConstruct
	public void scheduleHealthChecks() {
		WebClient wc = webClientBuilder.build();

		Flux.interval(java.time.Duration.ZERO, java.time.Duration.ofSeconds(5)).doOnNext(tick -> log.info("Health check main tick: {}", tick))
				.flatMap(tick -> wc.get().uri(mainHealthUrl).retrieve().bodyToMono(ServiceHealthResponse.class).doOnNext(mainHealthCache::set).doOnError(e -> log.warn("Main health check failed: {}", e.getMessage())).onErrorResume(e -> Mono.empty()))
				.subscribe();

		Flux.interval(java.time.Duration.ZERO, java.time.Duration.ofSeconds(5)).doOnNext(tick -> log.info("Health check main tick: {}", tick)).flatMap(
				tick -> wc.get().uri(fallBackHealthUrl).retrieve().bodyToMono(ServiceHealthResponse.class).doOnNext(fallbackHealthCache::set).doOnError(e -> log.warn("Fallback health check failed: {}", e.getMessage()))
						.onErrorResume(e -> Mono.empty())).subscribe();
	}

	@PostConstruct
	public void startPaymentWorker() {
		paymentQueue.asFlux()
				.concatMap(this::processPaymentInternal) // sequential processing
				.subscribe(result -> log.info("Processed payment: {}", result),
						error -> log.error("Payment worker error", error));
	}

	private Mono<String> processPaymentInternal(PaymentRequest req) {
		return processPayment(req); // or callProcessor(...) as needed
	}

	public Mono<String> enqueuePayment(PaymentRequest req) {
		Sinks.EmitResult emitResult = paymentQueue.tryEmitNext(req);
		if (emitResult.isSuccess()) {
			return Mono.just("Enqueued");
		} else {
			return Mono.error(new IllegalStateException("Queue is full or closed"));
		}
	}

	private Mono<String> processPayment(PaymentRequest req) {

		log.info("Processing payment request: {}", req);

		return repo.existsByCorrelationId(req.getCorrelationId()).flatMap(exists -> {
			if (exists) {
				log.error("Payment already exists: {}", req.getCorrelationId());
				return Mono.error(new IllegalArgumentException("Payment with correlationId " + req.getCorrelationId() + " already exists"));
			}
			log.info("Payment not exists: {}", req.getCorrelationId());
			return tryMainProcessor(req);
		});
	}

	public Mono<String> tryMainProcessor(PaymentRequest req) {

		log.info("Trying main payment processor: {}, healthCheck {}", req, mainHealthCache.get());

		if (mainHealthCache.get() != null && mainHealthCache.get().isFailing()) {
			log.info("Main payment processor is failing, using fallback");
			return tryFallBackProcessor(req);
		}

		var minResponseTime = mainHealthCache.get() != null ? mainHealthCache.get().getMinResponseTime() : 100;

		if (minResponseTime > 1000) {
			return tryFallBackProcessor(req);
		}

		return callProcessor(mainPayUrl, req, "DEFAULT");
	}

	public Mono<String> tryFallBackProcessor(PaymentRequest req) {
		log.info("Trying fallback payment processor: {}, healthCheck {}", req, fallbackHealthCache.get());

		if (fallbackHealthCache.get() == null || fallbackHealthCache.get().isFailing()) {
			log.info("Fallback payment processor is failing, will retry main after 1s");
			enqueuePayment(req);
			return Mono.error(new IllegalArgumentException("Fallback is not health. Enqueuing request " + req));
		}

		return callProcessor(fallbackPayUrl, req, "FALLBACK");

	}

	private Mono<String> callProcessor(String url, PaymentRequest req, String type) {
		WebClient wc = webClientBuilder.build();

		log.info("Calling processor: {} {}", req, url);

		return wc.post().uri(url).bodyValue(req).retrieve().bodyToMono(String.class).flatMap(resp -> saveAfterProcess(req, type)).thenReturn(type);
	}

	private Mono<Void> saveAfterProcess(PaymentRequest req, String type) {

		log.info("Saving after process: {}, {}", req, type);

		Payment p = Payment.builder().correlationId(req.getCorrelationId()).amount(req.getAmount()).createdAt(LocalDateTime.now(ZoneOffset.UTC)).paymentType(type).build();
		return repo.save(p).then();
	}

	public Mono<PaymentsSummaryResponse> getPaymentsSummary(ZonedDateTime from, ZonedDateTime to) {

		log.info("Getting payments summary, from: {}, to: {}", from, to);

		var f = from != null ? from.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime() : null;
		var t = to != null ? to.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime() : null;

		Mono<PaymentsSummaryResponse.PaymentSummary> def = db.sql("""
				SELECT COUNT(*) AS cnt, COALESCE(SUM(amount),0) AS sum
				FROM payments
				WHERE payment_type = :type
				  AND (:from IS NULL OR created_at >= :from)
				  AND (:to IS NULL OR created_at <= :to)
				""").bind("type", "DEFAULT").bind("from", f).bind("to", t).map(r -> new PaymentsSummaryResponse.PaymentSummary(r.get("cnt", Long.class), r.get("sum", BigDecimal.class))).one();

		Mono<PaymentsSummaryResponse.PaymentSummary> fb = db.sql("""
				SELECT COUNT(*) AS cnt, COALESCE(SUM(amount),0) AS sum
				FROM payments
				WHERE payment_type = :type
				  AND (:from IS NULL OR created_at >= :from)
				  AND (:to IS NULL OR created_at <= :to)
				""").bind("type", "FALLBACK").bind("from", f).bind("to", t).map(r -> new PaymentsSummaryResponse.PaymentSummary(r.get("cnt", Long.class), r.get("sum", BigDecimal.class))).one();

		return Mono.zip(def, fb).map(tu -> PaymentsSummaryResponse.builder().defaultSummary(tu.getT1()).fallback(tu.getT2()).build()).doOnNext(summary -> log.info("Payments summary result: {}", summary));

	}
}
