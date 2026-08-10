package com.agenthub.approval.repository;

import com.agenthub.approval.entity.ApprovalRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, Long> {

    Page<ApprovalRequest> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);
    Page<ApprovalRequest> findByTenantIdAndStatusOrderByCreatedAtDesc(Long tenantId, String status, Pageable pageable);

    Page<ApprovalRequest> findByRequesterIdOrderByCreatedAtDesc(Long requesterId, Pageable pageable);
    Page<ApprovalRequest> findByTenantIdAndRequesterIdOrderByCreatedAtDesc(Long tenantId, Long requesterId, Pageable pageable);
    Optional<ApprovalRequest> findByIdAndTenantId(Long id, Long tenantId);

    long countByStatus(String status);
    long countByTenantIdAndStatus(Long tenantId, String status);
}
