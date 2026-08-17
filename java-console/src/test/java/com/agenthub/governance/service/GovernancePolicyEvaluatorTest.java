package com.agenthub.governance.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GovernancePolicyEvaluatorTest {
    private final GovernancePolicyEvaluator evaluator = new GovernancePolicyEvaluator();

    @Test
    void appliesOrderedAbacPolicyAndDefaultsToDeny() {
        List<Map<String, Object>> policies = List.of(
                Map.of("id", 1L, "name", "deny confidential exports", "effect", "deny", "enabled", true,
                        "resourceType", "dataset", "actionPattern", "export*",
                        "conditions", Map.of("dataClassification", "confidential")),
                Map.of("id", 2L, "name", "allow finance", "effect", "allow", "enabled", true,
                        "resourceType", "dataset", "actionPattern", "export*",
                        "conditions", Map.of("department", "finance"))
        );

        assertFalse(evaluator.evaluateAccess("dataset", "export.csv",
                Map.of("department", "finance", "dataClassification", "confidential"), policies).allowed());
        assertTrue(evaluator.evaluateAccess("dataset", "export.csv",
                Map.of("department", "finance", "dataClassification", "internal"), policies).allowed());
        assertFalse(evaluator.evaluateAccess("agent", "delete", Map.of(), policies).allowed());
    }

    @Test
    void matchesApprovalPolicyAcrossAmountClassificationAndCaller() {
        List<Map<String, Object>> policies = List.of(
                Map.of("id", 9L, "name", "large confidential tool call", "decision", "dual", "enabled", true,
                        "slaMinutes", 15, "conditions", Map.of("amountMin", 5000, "dataClassification", "confidential",
                                "callerType", "external", "tool", List.of("refund.execute", "payment.send")))
        );

        GovernancePolicyEvaluator.ApprovalDecision matched = evaluator.evaluateApproval(
                Map.of("amount", 9000, "dataClassification", "confidential", "callerType", "external", "tool", "refund.execute"), policies);
        assertEquals("dual", matched.decision());
        assertEquals(15, matched.slaMinutes());

        assertEquals("single", evaluator.evaluateApproval(
                Map.of("amount", 100, "dataClassification", "internal", "callerType", "employee", "tool", "search"), policies).decision());
    }

    @Test
    void treatsEmptyListConditionAsNoRestriction() {
        GovernancePolicyEvaluator.ApprovalDecision decision = evaluator.evaluateApproval(
                Map.of("amount", 10),
                List.of(Map.of("id", 12L, "name", "default low risk", "decision", "auto_approve",
                        "enabled", true, "slaMinutes", 5, "conditions", Map.of("tool", List.of()))));

        assertEquals("auto_approve", decision.decision());
    }
}
