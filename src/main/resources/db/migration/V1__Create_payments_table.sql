CREATE TABLE IF NOT EXISTS payments (
                                        id BIGSERIAL PRIMARY KEY,
                                        correlation_id VARCHAR(255) NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    payment_type VARCHAR(50) NOT NULL CHECK (payment_type IN ('DEFAULT', 'FALLBACK')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_payments_correlation_id UNIQUE (correlation_id)
    );

-- Optimized indexes for summary queries
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_payments_type_created_at
    ON payments (payment_type, created_at);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_payments_created_at
    ON payments (created_at);

-- Partial indexes for better performance
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_payments_default_created_at
    ON payments (created_at)
    WHERE payment_type = 'DEFAULT';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_payments_fallback_created_at
    ON payments (created_at)
    WHERE payment_type = 'FALLBACK';
