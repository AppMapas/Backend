package com.seminario.legaladministrator.modules.processes.repository;

import com.seminario.legaladministrator.modules.processes.LegalProcessStageEventEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LegalProcessStageEventRepository extends JpaRepository<LegalProcessStageEventEntity, Long> {
    @EntityGraph(attributePaths = {"fromStage", "toStage", "actor"})
    List<LegalProcessStageEventEntity> findByLegalProcessIdOrderByOccurredAtAscIdAsc(Long legalProcessId);
    Optional<LegalProcessStageEventEntity> findByRequestId(UUID requestId);
}
