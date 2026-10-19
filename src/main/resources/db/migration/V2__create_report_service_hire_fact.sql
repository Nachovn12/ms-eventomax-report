CREATE TABLE report_production_cancellation (
    production_id BIGINT PRIMARY KEY,
    cancelled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    event_id UUID NOT NULL
);

CREATE TABLE report_service_hire_fact (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    production_id BIGINT NOT NULL,
    service_id BIGINT NOT NULL,
    service_name VARCHAR(150) NOT NULL,
    unit_price NUMERIC(10, 2),
    contracted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    is_cancelled BOOLEAN NOT NULL DEFAULT FALSE,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_report_service_hire UNIQUE (production_id, service_id)
);

CREATE INDEX idx_service_hire_contracted ON report_service_hire_fact(contracted_at);
CREATE INDEX idx_service_hire_service_id ON report_service_hire_fact(service_id);
CREATE INDEX idx_service_hire_prod_id ON report_service_hire_fact(production_id);
