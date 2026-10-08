CREATE TABLE processed_event (
    event_id UUID PRIMARY KEY,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE report_production_fact (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    type VARCHAR(50) NOT NULL,
    production_id BIGINT NOT NULL,
    previous_status VARCHAR(50) NOT NULL,
    new_status VARCHAR(50) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    
    CONSTRAINT fk_report_fact_event FOREIGN KEY (event_id) REFERENCES processed_event(event_id),
    CONSTRAINT chk_previous_status CHECK (previous_status IN ('SOLICITADO', 'CONFIRMADO', 'EN_MONTAJE', 'EN_EJECUCION', 'CERRADO', 'CANCELADO')),
    CONSTRAINT chk_new_status CHECK (new_status IN ('SOLICITADO', 'CONFIRMADO', 'EN_MONTAJE', 'EN_EJECUCION', 'CERRADO', 'CANCELADO'))
);

CREATE INDEX idx_fact_occurred_at ON report_production_fact(occurred_at);
CREATE INDEX idx_fact_prod_occurred ON report_production_fact(production_id, occurred_at DESC);
CREATE INDEX idx_fact_status_occurred ON report_production_fact(new_status, occurred_at);

