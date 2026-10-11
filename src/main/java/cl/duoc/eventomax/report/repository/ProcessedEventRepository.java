package cl.duoc.eventomax.report.repository;

import cl.duoc.eventomax.report.model.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {
    
    /**
     * Inserta un evento procesado de forma idempotente.
     * Utiliza la cláusula nativa ON CONFLICT DO NOTHING (compatible con PostgreSQL y con H2 en modo PostgreSQL)
     * para manejar la idempotencia sin abortar la transacción actual en caso de duplicidad.
     * 
     * Retorna:
     * - 1: Si el evento es nuevo y fue insertado.
     * - 0: Si el evento ya existía (duplicado), sin lanzar DataIntegrityViolationException.
     * 
     * Nota (EMX-83): El futuro consumidor Kafka debe invocar esto dentro de una transacción (@Transactional)
     * y condicionar la inserción de ReportProductionFact a que este método retorne 1, en la misma transacción.
     */
    @Modifying
    @Query(value = "INSERT INTO processed_event (event_id, processed_at) VALUES (:eventId, :processedAt) ON CONFLICT DO NOTHING", nativeQuery = true)
    int insertIfNotExists(@Param("eventId") UUID eventId, @Param("processedAt") Instant processedAt);
}

