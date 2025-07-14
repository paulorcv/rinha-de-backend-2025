package br.com.paulorcv.rinha.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("payments")
public class Payment {
    @Id
    private Long id;

    @Column("correlation_id")
    private UUID correlationId;

    @Column("amount")
    private BigDecimal amount;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("payment_type")
    private String paymentType;
}
