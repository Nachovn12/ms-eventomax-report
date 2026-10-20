package cl.duoc.eventomax.report.repository;

import cl.duoc.eventomax.report.model.ReportServiceHireFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface ReportServiceHireFactRepository extends JpaRepository<ReportServiceHireFact, UUID> {

    boolean existsByProductionIdAndServiceId(Long productionId, Long serviceId);

    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE ReportServiceHireFact f
        SET f.isCancelled = true, f.cancelledAt = :cancelledAt
        WHERE f.productionId = :productionId
    """)
    int markCancelledForProduction(@Param("productionId") Long productionId, @Param("cancelledAt") Instant cancelledAt);

    @Query(value = """
        SELECT sub.service_id AS serviceId,
               sub.service_name AS serviceName,
               COUNT(DISTINCT sub.production_id) AS hireCount
        FROM (
            SELECT f.service_id,
                   f.production_id,
                   FIRST_VALUE(f.service_name) OVER (
                       PARTITION BY f.service_id
                       ORDER BY f.contracted_at DESC, f.production_id DESC
                   ) AS service_name
            FROM report_service_hire_fact f
            WHERE f.is_cancelled = false
              AND f.production_id NOT IN (SELECT c.production_id FROM report_production_cancellation c)
              AND f.contracted_at >= :from AND f.contracted_at <= :to
        ) sub
        GROUP BY sub.service_id, sub.service_name
        ORDER BY COUNT(DISTINCT sub.production_id) DESC, sub.service_id ASC
    """, nativeQuery = true)
    List<TopServiceProjection> findTopServices(@Param("from") Instant from, @Param("to") Instant to);
}
