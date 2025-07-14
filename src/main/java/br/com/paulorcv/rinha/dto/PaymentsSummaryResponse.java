package br.com.paulorcv.rinha.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentsSummaryResponse {
    @JsonProperty("default")
    private PaymentSummary defaultSummary;
    private PaymentSummary fallback;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentSummary {
        private Long totalRequests;
        private BigDecimal totalAmount;
    }
}
