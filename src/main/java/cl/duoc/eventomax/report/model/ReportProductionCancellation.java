package cl.duoc.eventomax.report.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "report_production_cancellation")
public class ReportProductionCancellation {

    @Id
    @Column(name = "production_id", nullable = false)
    private Long productionId;

    @Column(name = "cancelled_at", nullable = false)
    private Instant cancelledAt;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    public ReportProductionCancellation() {}

    public ReportProductionCancellation(Long productionId, Instant cancelledAt, UUID eventId) {
        this.productionId = productionId;
        this.cancelledAt = cancelledAt;
        this.eventId = eventId;
    }

    public Long getProductionId() { return productionId; }
    public void setProductionId(Long productionId) { this.productionId = productionId; }
    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }
    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }
}
