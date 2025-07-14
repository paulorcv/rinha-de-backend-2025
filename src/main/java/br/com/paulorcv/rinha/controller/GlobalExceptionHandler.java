package br.com.paulorcv.rinha.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.support.WebExchangeBindException;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<Map<String,String>>> handleValidation(WebExchangeBindException ex) {
        Map<String,String> errors = new HashMap<>();
        ex.getFieldErrors().forEach(fe -> errors.put(fe.getField(), fe.getDefaultMessage()));
        return Mono.just(ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(errors));
    }
}
