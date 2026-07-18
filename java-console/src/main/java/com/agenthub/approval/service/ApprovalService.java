package com.agenthub.approval.service;

import com.agenthub.approval.entity.ApprovalRequest;
import com.agenthub.approval.repository.ApprovalRequestRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class ApprovalService {

    private final ApprovalRequestRepository approvalRepository;

    public ApprovalService(ApprovalRequestRepository approvalRepository) {
        this.approvalRepository = approvalRepository;
    }

    public ApprovalRequest createRequest(String sessionId, Long agentId, Long toolId,
                                          String toolName, Long requesterId,
                                          String reason, String context) {
        ApprovalRequest req = ApprovalRequest.builder()
                .sessionId(sessionId)
                .agentId(agentId)
                .toolId(toolId)
                .toolName(toolName)
                .requesterId(requesterId)
                .reason(reason)
                .context(context)
                .status("pending")
                .tenantId(0L)
                .build();
        return approvalRepository.save(req);
    }

    public ApprovalRequest approve(Long id, String comment) {
        ApprovalRequest req = getRequest(id);
        if (!"pending".equals(req.getStatus())) {
            throw new IllegalArgumentException("审批已处理，无法重复操作");
        }
        req.setStatus("approved");
        req.setResolvedAt(LocalDateTime.now());
        return approvalRepository.save(req);
    }

    public ApprovalRequest reject(Long id, String comment) {
        ApprovalRequest req = getRequest(id);
        if (!"pending".equals(req.getStatus())) {
            throw new IllegalArgumentException("审批已处理，无法重复操作");
        }
        req.setStatus("rejected");
        req.setResolvedAt(LocalDateTime.now());
        return approvalRepository.save(req);
    }

    public ApprovalRequest getRequest(Long id) {
        return approvalRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("审批请求不存在: " + id));
    }

    public Page<ApprovalRequest> listPending(Pageable pageable) {
        return approvalRepository.findByStatusOrderByCreatedAtDesc("pending", pageable);
    }

    public Page<ApprovalRequest> listByRequester(Long requesterId, Pageable pageable) {
        return approvalRepository.findByRequesterIdOrderByCreatedAtDesc(requesterId, pageable);
    }

    public Map<String, Object> stats() {
        return Map.of(
                "pending", approvalRepository.countByStatus("pending"),
                "approved", approvalRepository.countByStatus("approved"),
                "rejected", approvalRepository.countByStatus("rejected")
        );
    }
}
