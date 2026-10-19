package cl.duoc.eventomax.report.repository;

import cl.duoc.eventomax.report.model.ReportProductionCancellation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReportProductionCancellationRepository extends JpaRepository<ReportProductionCancellation, Long> {
    boolean existsByProductionId(Long productionId);
}
