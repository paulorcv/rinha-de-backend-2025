package br.com.paulorcv.rinha.controller;

import br.com.paulorcv.rinha.dto.PaymentRequest;
import br.com.paulorcv.rinha.dto.PaymentResponse;
import br.com.paulorcv.rinha.dto.PaymentsSummaryResponse;
import br.com.paulorcv.rinha.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.ZonedDateTime;

@RestController
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final PaymentService service;

    @PostMapping("/payments")
    public Mono<ResponseEntity<PaymentResponse>> processPayment(
            @Valid @RequestBody Mono<PaymentRequest> reqMono) {

        log.info("Iniciando processPayment");

        return reqMono
                .flatMap(req ->
                        service.processPayment(req)
                                .map(type -> new PaymentResponse("SUCCESS", "Payment processed via processor: " + type))
                )
                .map(resp -> ResponseEntity.status(HttpStatus.CREATED).body(resp))
                .onErrorResume(IllegalArgumentException.class, ex ->
                        Mono.just(ResponseEntity
                                .status(HttpStatus.CONFLICT)
                                .body(new PaymentResponse("ERROR", ex.getMessage())))
                );
    }

    @GetMapping("/payments-summary")
    public Mono<PaymentsSummaryResponse> getSummary(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            ZonedDateTime from,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            ZonedDateTime to) {

        log.info("Iniciando processPaymentSummary");

        return service.getPaymentsSummary(from, to);
    }
}
