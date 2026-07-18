package com.agenthub.audit.repository;

import com.agenthub.audit.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT a FROM AuditLog a WHERE " +
           "(:eventType IS NULL OR a.eventType = :eventType) AND " +
           "(:userId IS NULL OR a.userId = :userId) AND " +
           "(:startTime IS NULL OR a.createdAt >= :startTime) AND " +
           "(:endTime IS NULL OR a.createdAt <= :endTime) " +
           "ORDER BY a.createdAt DESC")
    Page<AuditLog> search(@Param("eventType") String eventType,
                          @Param("userId") Long userId,
                          @Param("startTime") LocalDateTime startTime,
                          @Param("endTime") LocalDateTime endTime,
                          Pageable pageable);
}
