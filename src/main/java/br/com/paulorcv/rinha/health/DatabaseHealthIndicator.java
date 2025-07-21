package br.com.paulorcv.rinha.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
public class DatabaseHealthIndicator implements ReactiveHealthIndicator {
    private final DatabaseClient databaseClient;

    public DatabaseHealthIndicator(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    @Override
    public Mono<Health> health() {
        return databaseClient
                .sql("SELECT 1 as health_check")
                .fetch()
                .one()
                .map(result -> Health.up()
                        .withDetail("database", "PostgreSQL")
                        .withDetail("status", "Connected")
                        .withDetail("query_result", result.get("health_check"))
                        .build())
                .onErrorResume(ex -> Mono.just(Health.down()
                        .withDetail("database", "PostgreSQL")
                        .withDetail("error", ex.getMessage())
                        .withDetail("status", "Disconnected")
                        .build()))
                .timeout(Duration.ofSeconds(5))
                .onErrorReturn(Health.down()
                        .withDetail("database", "PostgreSQL")
                        .withDetail("error", "Health check timeout")
                        .build());
    }
}
