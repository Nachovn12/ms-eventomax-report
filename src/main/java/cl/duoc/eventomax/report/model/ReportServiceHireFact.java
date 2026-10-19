package cl.duoc.eventomax.report.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "report_service_hire_fact",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_report_service_hire", columnNames = {"production_id", "service_id"})
        }
)
public class ReportServiceHireFact {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "production_id", nullable = false)
    private Long productionId;

    @Column(name = "service_id", nullable = false)
    private Long serviceId;

    @Column(name = "service_name", nullable = false, length = 150)
    private String serviceName;

    @Column(name = "unit_price", precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "contracted_at", nullable = false)
    private Instant contractedAt;

    @Column(name = "is_cancelled", nullable = false)
    private boolean isCancelled = false;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    public ReportServiceHireFact() {}

    public ReportServiceHireFact(
            UUID eventId,
            Long productionId,
            Long serviceId,
            String serviceName,
            BigDecimal unitPrice,
            Instant contractedAt,
            boolean isCancelled) {
        this.eventId = eventId;
        this.productionId = productionId;
        this.serviceId = serviceId;
        this.serviceName = serviceName;
        this.unitPrice = unitPrice;
        this.contractedAt = contractedAt;
        this.isCancelled = isCancelled;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }
    public Long getProductionId() { return productionId; }
    public void setProductionId(Long productionId) { this.productionId = productionId; }
    public Long getServiceId() { return serviceId; }
    public void setServiceId(Long serviceId) { this.serviceId = serviceId; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public Instant getContractedAt() { return contractedAt; }
    public void setContractedAt(Instant contractedAt) { this.contractedAt = contractedAt; }
    public boolean isCancelled() { return isCancelled; }
    public void setCancelled(boolean isCancelled) { this.isCancelled = isCancelled; }
    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }
}
