package cl.duoc.eventomax.report.repository;

import cl.duoc.eventomax.report.dto.TransitionCountDto;
import cl.duoc.eventomax.report.model.ReportProductionFact;
import cl.duoc.eventomax.report.model.enums.ProductionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public interface ReportProductionFactRepository extends JpaRepository<ReportProductionFact, UUID> {

    /**
     * Consulta base ordenada por producción, mayor occurred_at y desempate determinista por mayor event_id (sin MAX sobre UUID).
     */
    @Query("SELECT f FROM ReportProductionFact f ORDER BY f.productionId, f.occurredAt DESC, f.eventId DESC")
    List<ReportProductionFact> findAllOrderByProductionIdAndOccurredAtDescEventIdDesc();

    /**
     * Obtiene el estado actual de cada producción basándose en el mayor occurred_at
     * y desempate determinista por mayor event_id (primer registro ordenado por cada production_id).
     */
    default List<ReportProductionFact> findCurrentStatusOfAllProductions() {
        return findAllOrderByProductionIdAndOccurredAtDescEventIdDesc().stream()
                .collect(Collectors.toMap(
                        ReportProductionFact::getProductionId,
                        fact -> fact,
                        (existing, replacement) -> existing,
                        LinkedHashMap::new
                ))
                .values()
                .stream()
                .toList();
    }

    /**
     * Consulta hechos dentro de un rango temporal ordenados por productionId, occurredAt DESC y eventId DESC.
     */
    @Query("SELECT f FROM ReportProductionFact f WHERE f.occurredAt >= :from AND f.occurredAt <= :to ORDER BY f.productionId, f.occurredAt DESC, f.eventId DESC")
    List<ReportProductionFact> findFactsBetweenOrdered(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * Conteo total de cambios de estado en un rango temporal.
     */
    @Query("SELECT COUNT(f) FROM ReportProductionFact f WHERE f.occurredAt >= :from AND f.occurredAt <= :to")
    long countByOccurredAtBetween(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * Conteo de hechos con un new_status específico en un rango temporal.
     */
    @Query("SELECT COUNT(f) FROM ReportProductionFact f WHERE f.newStatus = :status AND f.occurredAt >= :from AND f.occurredAt <= :to")
    long countByNewStatusAndOccurredAtBetween(@Param("status") ProductionStatus status, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * Conteo de transiciones previous_status -> new_status en un rango temporal.
     */
    @Query("SELECT new cl.duoc.eventomax.report.dto.TransitionCountDto(f.previousStatus, f.newStatus, COUNT(f)) " +
           "FROM ReportProductionFact f " +
           "WHERE f.occurredAt >= :from AND f.occurredAt <= :to " +
           "GROUP BY f.previousStatus, f.newStatus " +
           "ORDER BY COUNT(f) DESC")
    List<TransitionCountDto> findTransitionsCountBetween(@Param("from") Instant from, @Param("to") Instant to);
}
