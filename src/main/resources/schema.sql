CREATE TABLE payments (
                          id BIGINT PRIMARY KEY AUTO_INCREMENT,
                          correlation_id UUID NOT NULL UNIQUE,
                          amount DECIMAL(19,2) NOT NULL,
                          created_at TIMESTAMP NOT NULL,
                          payment_type VARCHAR(50) NOT NULL
);
