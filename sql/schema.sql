-- Create payments table
CREATE TABLE IF NOT EXISTS payments (
    id BIGSERIAL PRIMARY KEY,
    correlation_id UUID NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    payment_type VARCHAR(50) NOT NULL CHECK (payment_type IN ('DEFAULT', 'FALLBACK')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT uk_payments_correlation_id UNIQUE (correlation_id)
);

-- Create optimized indexes
CREATE INDEX IF NOT EXISTS idx_payments_type_created_at 
    ON payments (payment_type, created_at);

CREATE INDEX IF NOT EXISTS idx_payments_created_at 
    ON payments (created_at);

-- Partial indexes for better performance
CREATE INDEX IF NOT EXISTS idx_payments_default_created_at 
    ON payments (created_at) 
    WHERE payment_type = 'DEFAULT';

CREATE INDEX IF NOT EXISTS idx_payments_fallback_created_at 
    ON payments (created_at) 
    WHERE payment_type = 'FALLBACK';