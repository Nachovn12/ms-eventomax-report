package cl.duoc.eventomax.report.repository;

import cl.duoc.eventomax.report.model.ReportProductionFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReportProductionFactRepository extends JpaRepository<ReportProductionFact, UUID> {
    
    /**
     * Obtiene el estado actual de cada producción basándose en el mayor occurred_at.
     * En caso de empate (mismo occurred_at para la misma producción), desempata por mayor event_id
     * para garantizar que retorne una única fila por production_id.
     */
    @Query("SELECT f FROM ReportProductionFact f WHERE f.occurredAt = " +
           "(SELECT MAX(f2.occurredAt) FROM ReportProductionFact f2 WHERE f2.productionId = f.productionId) " +
           "AND f.eventId = " +
           "(SELECT MAX(f3.eventId) FROM ReportProductionFact f3 WHERE f3.productionId = f.productionId AND f3.occurredAt = f.occurredAt)")
    List<ReportProductionFact> findCurrentStatusOfAllProductions();
}

