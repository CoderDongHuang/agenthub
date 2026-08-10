package com.agenthub.agent.repository;

import com.agenthub.agent.entity.AgentDefinition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AgentDefinitionRepository extends JpaRepository<AgentDefinition, Long> {

    Page<AgentDefinition> findByTenantIdOrderByUpdatedAtDesc(Long tenantId, Pageable pageable);
    Optional<AgentDefinition> findByIdAndTenantId(Long id, Long tenantId);

    List<AgentDefinition> findByStatus(String status);

    long countByStatus(String status);

    long countByTenantId(Long tenantId);
    long countByTenantIdAndStatus(Long tenantId, String status);
}
