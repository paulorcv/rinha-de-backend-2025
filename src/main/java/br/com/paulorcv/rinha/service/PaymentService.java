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
import java.time.temporal.ChronoUnit;
import java.util.Map;
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

	private final Sinks.Many<PaymentRequest> paymentQueue = Sinks.many().multicast()
			.onBackpressureBuffer(20000, false);

	@PostConstruct
	public void scheduleHealthChecks() {
		WebClient wc = webClientBuilder.build();

		Flux.interval(java.time.Duration.ZERO, java.time.Duration.ofSeconds(10)).doOnNext(tick -> log.info("Health check main tick: {}", tick))
				.flatMap(tick -> wc.get().uri(mainHealthUrl).retrieve().bodyToMono(ServiceHealthResponse.class).doOnNext(mainHealthCache::set).doOnError(e -> log.warn("Main health check failed: {}", e.getMessage())).onErrorResume(e -> Mono.empty()))
				.subscribe();

		Flux.interval(java.time.Duration.ZERO, java.time.Duration.ofSeconds(10)).doOnNext(tick -> log.info("Health check main tick: {}", tick)).flatMap(
				tick -> wc.get().uri(fallBackHealthUrl).retrieve().bodyToMono(ServiceHealthResponse.class).doOnNext(fallbackHealthCache::set).doOnError(e -> log.warn("Fallback health check failed: {}", e.getMessage()))
						.onErrorResume(e -> Mono.empty())).subscribe();
	}

	@PostConstruct
	public void startPaymentWorker() {
		paymentQueue.asFlux().onBackpressureBuffer(20000) // Additional backpressure buffer
				.concatMap(this::processPaymentInternal) // sequential processing
				.subscribe(result -> log.info("Processed payment: {}", result), error -> log.error("Payment worker error", error));
	}

	private Mono<String> processPaymentInternal(PaymentRequest req) {
		return processPayment(req).onErrorResume(error -> {
			log.error("Failed to process payment: {}", req.getCorrelationId(), error);
			// Don't re-enqueue on error to prevent infinite loops
			return Mono.empty();
		});
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
//			enqueuePayment(req);
//			return Mono.error(new IllegalArgumentException("Fallback is not health. Enqueuing request " + req));
			return Mono.error(new IllegalStateException("Both main and fallback processors are unavailable"));
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

		// Use optimized query with better indexing
		Mono<PaymentsSummaryResponse.PaymentSummary> defaultSummary = getPaymentSummaryByType("DEFAULT", f, t);
		Mono<PaymentsSummaryResponse.PaymentSummary> fallbackSummary = getPaymentSummaryByType("FALLBACK", f, t);

		return Mono.zip(defaultSummary, fallbackSummary).map(tuple -> PaymentsSummaryResponse.builder().defaultSummary(tuple.getT1()).fallback(tuple.getT2()).build()).doOnNext(summary -> log.info("Payments summary result: {}", summary));
	}

	private Mono<PaymentsSummaryResponse.PaymentSummary> getPaymentSummaryByType(String type, LocalDateTime from, LocalDateTime to) {

		// Use prepared statement with parameters to prevent SQL injection
		String sql = """
				SELECT 
				    COUNT(*) as cnt, 
				    COALESCE(SUM(amount), 0) as sum
				FROM payments 
				WHERE payment_type = $1
				  AND ($2::timestamp IS NULL OR created_at >= $2)
				  AND ($3::timestamp IS NULL OR created_at <= $3)
				""";

		return db.sql(sql).bind("$1", type).bind("$2", from).bind("$3", to).map(row -> new PaymentsSummaryResponse.PaymentSummary(row.get("cnt", Long.class), row.get("sum", BigDecimal.class))).one().onErrorResume(error -> {
			log.error("Error getting payment summary for type: {}", type, error);
			return Mono.just(new PaymentsSummaryResponse.PaymentSummary(0L, BigDecimal.ZERO));
		});
	}

	// Optimized method for large date ranges using materialized view
	public Mono<PaymentsSummaryResponse> getPaymentsSummaryOptimized(ZonedDateTime from, ZonedDateTime to) {
		if (from != null && to != null && ChronoUnit.DAYS.between(from, to) > 7) {
			return getPaymentsSummaryFromMaterializedView(from, to);
		}
		return getPaymentsSummary(from, to);
	}

	private Mono<PaymentsSummaryResponse> getPaymentsSummaryFromMaterializedView(ZonedDateTime from, ZonedDateTime to) {

		var f = from.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
		var t = to.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();

		String sql = """
				SELECT 
				    payment_type,
				    SUM(payment_count) as cnt,
				    SUM(total_amount) as sum
				FROM payments_daily_summary
				WHERE summary_date >= DATE_TRUNC('day', $1::timestamp)
				  AND summary_date <= DATE_TRUNC('day', $2::timestamp)
				GROUP BY payment_type
				""";

		return db.sql(sql).bind("$1", f).bind("$2", t).map(row -> Map.entry(row.get("payment_type", String.class), new PaymentsSummaryResponse.PaymentSummary(row.get("cnt", Long.class), row.get("sum", BigDecimal.class)))).all()
				.collectMap(Map.Entry::getKey, Map.Entry::getValue).map(summaryMap -> PaymentsSummaryResponse.builder().defaultSummary(summaryMap.getOrDefault("DEFAULT", new PaymentsSummaryResponse.PaymentSummary(0L, BigDecimal.ZERO)))
						.fallback(summaryMap.getOrDefault("FALLBACK", new PaymentsSummaryResponse.PaymentSummary(0L, BigDecimal.ZERO))).build());
	}
}
