package br.com.paulorcv.rinha.repository;

import br.com.paulorcv.rinha.model.Payment;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface PaymentRepository extends ReactiveCrudRepository<Payment, Long> {
    Mono<Boolean> existsByCorrelationId(UUID correlationId);
}
