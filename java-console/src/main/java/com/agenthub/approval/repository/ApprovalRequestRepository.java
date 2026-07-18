package com.agenthub.approval.repository;

import com.agenthub.approval.entity.ApprovalRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, Long> {

    Page<ApprovalRequest> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    Page<ApprovalRequest> findByRequesterIdOrderByCreatedAtDesc(Long requesterId, Pageable pageable);

    long countByStatus(String status);
}
