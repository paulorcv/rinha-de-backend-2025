package br.com.paulorcv.rinha.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

@Configuration
@EnableR2dbcRepositories(basePackages = "br.com.paulorcv.rinha.repository")
public class R2dbcConfig {
}
