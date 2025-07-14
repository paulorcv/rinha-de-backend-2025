package br.com.paulorcv.rinha.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class PaymentRequest {
    @NotNull
    private UUID correlationId;

    @NotNull
    @Positive
    private BigDecimal amount;
}
