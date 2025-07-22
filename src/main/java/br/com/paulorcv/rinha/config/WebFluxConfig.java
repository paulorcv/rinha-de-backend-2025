package br.com.paulorcv.rinha.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebFlux
@Slf4j
public class WebFluxConfig implements WebFluxConfigurer {

    @Bean
    public WebExceptionHandler exceptionHandler() {
        return (ServerWebExchange exchange, Throwable ex) -> {
            // Line 19: Custom error handling logic that avoids logging stack traces
            if (ex instanceof IllegalStateException) {
                // Just log the message without stack trace
                log.error("Business error: {}", ex.getMessage());

                // Prepare error response
                exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
                exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

                // Create JSON error response
                String errorJson = String.format("{\"error\":\"%s\"}", ex.getMessage());
                byte[] bytes = errorJson.getBytes(StandardCharsets.UTF_8);
                DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);

                // Write response and complete
                return exchange.getResponse().writeWith(Mono.just(buffer));
            }

            // For other exceptions, let them propagate normally (with stack trace)
            log.error("Unhandled exception", ex);
            return Mono.error(ex);
        };
    }

    // Other WebFlux configuration methods as needed
}