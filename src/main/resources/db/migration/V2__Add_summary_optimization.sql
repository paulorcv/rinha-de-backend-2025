-- Materialized view for faster summary queries
CREATE MATERIALIZED VIEW payments_daily_summary AS
SELECT 
    DATE_TRUNC('day', created_at) as summary_date,
    payment_type,
    COUNT(*) as payment_count,
    SUM(amount) as total_amount,
    AVG(amount) as avg_amount,
    MIN(amount) as min_amount,
    MAX(amount) as max_amount
FROM payments
GROUP BY DATE_TRUNC('day', created_at), payment_type;

CREATE UNIQUE INDEX ON payments_daily_summary (summary_date, payment_type);

-- Function to refresh materialized view
CREATE OR REPLACE FUNCTION refresh_payments_summary()
RETURNS void AS $$
BEGIN
    REFRESH MATERIALIZED VIEW CONCURRENTLY payments_daily_summary;
END;
$$ LANGUAGE plpgsql;
