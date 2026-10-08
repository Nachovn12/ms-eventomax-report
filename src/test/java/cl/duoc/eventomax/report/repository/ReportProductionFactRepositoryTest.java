package cl.duoc.eventomax.report.repository;

import cl.duoc.eventomax.report.model.ProcessedEvent;
import cl.duoc.eventomax.report.model.ReportProductionFact;
import cl.duoc.eventomax.report.model.enums.ProductionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true"
})
public class ReportProductionFactRepositoryTest {

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ReportProductionFactRepository reportProductionFactRepository;
    
    @Autowired
    private EntityManager entityManager;

    @Test
    void testValidInsertionAndFindCurrentStatus() {
        Long productionId = 100L;
        
        // Insert event 1 (occurred first)
        UUID eventId1 = UUID.randomUUID();
        int inserted1 = processedEventRepository.insertIfNotExists(eventId1, Instant.now());
        assertThat(inserted1).isEqualTo(1);
        
        ReportProductionFact fact1 = new ReportProductionFact();
        fact1.setEventId(eventId1);
        fact1.setType("ProductionStatusChanged");
        fact1.setProductionId(productionId);
        fact1.setPreviousStatus(ProductionStatus.SOLICITADO);
        fact1.setNewStatus(ProductionStatus.CONFIRMADO);
        fact1.setOccurredAt(Instant.parse("2026-10-08T10:00:00Z"));
        reportProductionFactRepository.save(fact1);

        // Insert event 2 (occurred later)
        UUID eventId2 = UUID.randomUUID();
        int inserted2 = processedEventRepository.insertIfNotExists(eventId2, Instant.now());
        assertThat(inserted2).isEqualTo(1);

        ReportProductionFact fact2 = new ReportProductionFact();
        fact2.setEventId(eventId2);
        fact2.setType("ProductionStatusChanged");
        fact2.setProductionId(productionId);
        fact2.setPreviousStatus(ProductionStatus.CONFIRMADO);
        fact2.setNewStatus(ProductionStatus.EN_MONTAJE);
        fact2.setOccurredAt(Instant.parse("2026-10-08T11:00:00Z"));
        reportProductionFactRepository.save(fact2);
        
        // Find current status
        List<ReportProductionFact> currentStatuses = reportProductionFactRepository.findCurrentStatusOfAllProductions();
        
        assertThat(currentStatuses).hasSize(1);
        assertThat(currentStatuses.get(0).getNewStatus()).isEqualTo(ProductionStatus.EN_MONTAJE);
    }
    
    @Test
    void testCurrentStatusWithTieBreaker() {
        Long productionId = 200L;
        Instant sameOccurredAt = Instant.parse("2026-10-08T12:00:00Z");
        
        UUID eventIdA = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID eventIdB = UUID.fromString("00000000-0000-0000-0000-000000000002");
        
        // Insert A
        processedEventRepository.insertIfNotExists(eventIdA, Instant.now());
        ReportProductionFact factA = new ReportProductionFact();
        factA.setEventId(eventIdA);
        factA.setType("ProductionStatusChanged");
        factA.setProductionId(productionId);
        factA.setPreviousStatus(ProductionStatus.SOLICITADO);
        factA.setNewStatus(ProductionStatus.CONFIRMADO);
        factA.setOccurredAt(sameOccurredAt);
        reportProductionFactRepository.save(factA);

        // Insert B (tie-breaker should prefer this because eventIdB > eventIdA)
        processedEventRepository.insertIfNotExists(eventIdB, Instant.now());
        ReportProductionFact factB = new ReportProductionFact();
        factB.setEventId(eventIdB);
        factB.setType("ProductionStatusChanged");
        factB.setProductionId(productionId);
        factB.setPreviousStatus(ProductionStatus.CONFIRMADO);
        factB.setNewStatus(ProductionStatus.EN_MONTAJE);
        factB.setOccurredAt(sameOccurredAt);
        reportProductionFactRepository.save(factB);
        
        // Ensure changes are flushed
        reportProductionFactRepository.flush();

        List<ReportProductionFact> currentStatuses = reportProductionFactRepository.findCurrentStatusOfAllProductions();
        
        assertThat(currentStatuses).hasSize(1);
        assertThat(currentStatuses.get(0).getEventId()).isEqualTo(eventIdB);
        assertThat(currentStatuses.get(0).getNewStatus()).isEqualTo(ProductionStatus.EN_MONTAJE);
    }

    @Test
    void testIdempotencyOnDuplicateEventId() {
        UUID eventId = UUID.randomUUID();
        
        // First insertion
        int inserted1 = processedEventRepository.insertIfNotExists(eventId, Instant.now());
        assertThat(inserted1).isEqualTo(1);
        
        // Duplicate insertion
        int inserted2 = processedEventRepository.insertIfNotExists(eventId, Instant.now());
        assertThat(inserted2).isEqualTo(0);
    }

    @Test
    void testRejectsInvalidStatusByCheckConstraint() {
        UUID eventId = UUID.randomUUID();
        processedEventRepository.insertIfNotExists(eventId, Instant.now());
        
        // Attempt to insert an invalid status bypassing JPA Enum checking using Native Query
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery(
                "INSERT INTO report_production_fact (id, event_id, type, production_id, previous_status, new_status, occurred_at) " +
                "VALUES (:id, :eventId, 'ProductionStatusChanged', 300, 'SOLICITADO', 'ESTADO_INVALIDO', :occurredAt)")
                .setParameter("id", UUID.randomUUID())
                .setParameter("eventId", eventId)
                .setParameter("occurredAt", Instant.now())
                .executeUpdate();
        }).isInstanceOf(Exception.class); // Depending on dialect, could be DataIntegrityViolationException or PersistenceException wrapping constraint violation
    }
}

