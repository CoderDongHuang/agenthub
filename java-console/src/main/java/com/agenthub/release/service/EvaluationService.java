package com.agenthub.release.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
public class EvaluationService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AgentVersionService versionService;

    public EvaluationService(JdbcTemplate jdbc, ObjectMapper mapper, AgentVersionService versionService) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.versionService = versionService;
    }

    public List<Map<String, Object>> listDatasets(Long tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT dataset.id,dataset.name,dataset.description,dataset.target_type,dataset.pass_threshold," +
                        "dataset.status,dataset.created_at,dataset.updated_at,COUNT(test.id) AS case_count " +
                        "FROM evaluation_dataset dataset LEFT JOIN evaluation_case test ON test.dataset_id=dataset.id " +
                        "WHERE dataset.tenant_id=? GROUP BY dataset.id ORDER BY dataset.updated_at DESC", tenantId);
        return rows;
    }

    @Transactional
    public Map<String, Object> createDataset(Long tenantId, Long userId, Map<String, Object> body) {
        String name = required(body, "name");
        String targetType = required(body, "targetType");
        if (!Set.of("prompt", "tool", "rag").contains(targetType)) {
            throw new IllegalArgumentException("targetType must be prompt, tool or rag");
        }
        BigDecimal threshold = decimal(body.get("passThreshold"), BigDecimal.valueOf(80));
        if (threshold.compareTo(BigDecimal.ZERO) < 0 || threshold.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("passThreshold must be between 0 and 100");
        }
        Long id = jdbc.queryForObject(
                "INSERT INTO evaluation_dataset(tenant_id,name,description,target_type,pass_threshold,created_by) " +
                        "VALUES (?,?,?,?,?,?) RETURNING id", Long.class, tenantId, name,
                text(body.get("description")), targetType, threshold, userId);
        Object rawCases = body.get("cases");
        if (rawCases instanceof List<?> cases) {
            for (Object rawCase : cases) addCaseInternal(tenantId, id, asMap(rawCase));
        }
        return getDataset(tenantId, id);
    }

    public Map<String, Object> getDataset(Long tenantId, Long datasetId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,name,description,target_type,pass_threshold,status,created_at,updated_at " +
                        "FROM evaluation_dataset WHERE id=? AND tenant_id=?", datasetId, tenantId);
        if (rows.isEmpty()) throw new NoSuchElementException("Evaluation dataset not found");
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("cases", listCases(tenantId, datasetId));
        return result;
    }

    public List<Map<String, Object>> listCases(Long tenantId, Long datasetId) {
        requireDataset(tenantId, datasetId);
        return jdbc.queryForList(
                "SELECT test.id,test.name,test.input::text AS input,test.expected::text AS expected," +
                        "test.assertion_type,test.weight,test.created_at FROM evaluation_case test " +
                        "JOIN evaluation_dataset dataset ON dataset.id=test.dataset_id " +
                        "WHERE test.dataset_id=? AND dataset.tenant_id=? ORDER BY test.id",
                datasetId, tenantId).stream().map(row -> {
                    Map<String, Object> item = new LinkedHashMap<>(row);
                    item.put("input", parse(row.get("input")));
                    item.put("expected", parse(row.get("expected")));
                    return item;
                }).toList();
    }

    public Map<String, Object> addCase(Long tenantId, Long datasetId, Map<String, Object> body) {
        return addCaseInternal(tenantId, datasetId, body);
    }

    @Transactional
    public Map<String, Object> run(Long tenantId, Long datasetId, Long agentId, Long versionId,
                                   Map<String, Object> body) {
        Map<String, Object> dataset = getDataset(tenantId, datasetId);
        if (versionId != null && agentId != null) versionService.get(tenantId, agentId, versionId);
        Map<String, Object> outputs = asMap(body.getOrDefault("outputs", Map.of()));
        List<Map<String, Object>> cases = castList(dataset.get("cases"));
        if (cases.isEmpty()) throw new IllegalStateException("Evaluation dataset has no cases");

        BigDecimal earned = BigDecimal.ZERO;
        BigDecimal possible = BigDecimal.ZERO;
        int passedCount = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        for (Map<String, Object> testCase : cases) {
            String caseId = String.valueOf(testCase.get("id"));
            Object actual = outputs.get(caseId);
            if (actual == null) actual = outputs.get(String.valueOf(testCase.get("name")));
            BigDecimal weight = decimal(testCase.get("weight"), BigDecimal.ONE);
            possible = possible.add(weight);
            AssertionResult result = evaluate(String.valueOf(testCase.get("assertion_type")),
                    asMap(testCase.get("expected")), actual);
            if (result.passed()) {
                earned = earned.add(weight);
                passedCount++;
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("caseId", testCase.get("id"));
            detail.put("name", testCase.get("name"));
            detail.put("passed", result.passed());
            detail.put("message", result.message());
            detail.put("actual", actual);
            details.add(detail);
        }
        BigDecimal score = possible.signum() == 0 ? BigDecimal.ZERO :
                earned.multiply(BigDecimal.valueOf(100)).divide(possible, 2, RoundingMode.HALF_UP);
        BigDecimal threshold = decimal(dataset.get("pass_threshold"), BigDecimal.valueOf(80));
        boolean passed = score.compareTo(threshold) >= 0;
        Long runId = jdbc.queryForObject(
                "INSERT INTO evaluation_run(tenant_id,dataset_id,agent_id,agent_version_id,status,score," +
                        "passed_cases,total_cases,details,completed_at) VALUES (?,?,?,?,?,?,?,?,?::jsonb,NOW()) RETURNING id",
                Long.class, tenantId, datasetId, agentId, versionId, passed ? "passed" : "failed", score,
                passedCount, cases.size(), json(details));
        if (versionId != null) refreshVersionGate(tenantId, versionId);
        return getRun(tenantId, runId);
    }

    public List<Map<String, Object>> listRuns(Long tenantId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.queryForList(
                "SELECT run.id,run.dataset_id,dataset.name AS dataset_name,dataset.target_type,run.agent_id," +
                        "run.agent_version_id,run.status,run.score,run.passed_cases,run.total_cases," +
                        "run.started_at,run.completed_at FROM evaluation_run run JOIN evaluation_dataset dataset " +
                        "ON dataset.id=run.dataset_id WHERE run.tenant_id=? ORDER BY run.started_at DESC LIMIT ?",
                tenantId, safeLimit);
    }

    public Map<String, Object> getRun(Long tenantId, Long runId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT run.id,run.dataset_id,dataset.name AS dataset_name,dataset.target_type,run.agent_id," +
                        "run.agent_version_id,run.status,run.score,run.passed_cases,run.total_cases," +
                        "run.details::text AS details,run.started_at,run.completed_at FROM evaluation_run run " +
                        "JOIN evaluation_dataset dataset ON dataset.id=run.dataset_id WHERE run.id=? AND run.tenant_id=?",
                runId, tenantId);
        if (rows.isEmpty()) throw new NoSuchElementException("Evaluation run not found");
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("details", parse(result.get("details")));
        return result;
    }

    AssertionResult evaluate(String assertionType, Map<String, Object> expected, Object actual) {
        String actualText = actual instanceof String ? (String) actual : json(actual == null ? Map.of() : actual);
        String expectedValue = text(expected.getOrDefault("value", expected.getOrDefault("text", "")));
        return switch (assertionType) {
            case "exact" -> new AssertionResult(actualText.trim().equals(expectedValue.trim()), "Expected exact match");
            case "regex" -> regex(expectedValue, actualText);
            case "json_contains" -> jsonContains(expected, actual);
            case "citation" -> citation(expected, actual);
            case "not_contains" -> new AssertionResult(!actualText.contains(expectedValue),
                    "Output must not contain: " + expectedValue);
            default -> new AssertionResult(actualText.contains(expectedValue), "Output must contain: " + expectedValue);
        };
    }

    private AssertionResult regex(String expression, String actual) {
        try {
            return new AssertionResult(Pattern.compile(expression, Pattern.DOTALL).matcher(actual).find(),
                    "Output must match regular expression");
        } catch (PatternSyntaxException exception) {
            return new AssertionResult(false, "Invalid expected regular expression");
        }
    }

    private AssertionResult jsonContains(Map<String, Object> expected, Object actual) {
        JsonNode expectedNode = mapper.valueToTree(expected.getOrDefault("value", expected));
        JsonNode actualNode;
        try {
            actualNode = actual instanceof String ? mapper.readTree((String) actual) : mapper.valueToTree(actual);
        } catch (Exception exception) {
            return new AssertionResult(false, "Actual output is not valid JSON");
        }
        boolean passed = expectedNode.fields().hasNext() && containsFields(actualNode, expectedNode);
        return new AssertionResult(passed, "Actual JSON must contain expected fields");
    }

    private boolean containsFields(JsonNode actual, JsonNode expected) {
        if (expected.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = expected.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!actual.has(field.getKey()) || !containsFields(actual.get(field.getKey()), field.getValue())) return false;
            }
            return true;
        }
        if (expected.isArray()) {
            if (!actual.isArray() || actual.size() < expected.size()) return false;
            for (int i = 0; i < expected.size(); i++) if (!containsFields(actual.get(i), expected.get(i))) return false;
            return true;
        }
        return Objects.equals(actual, expected);
    }

    private AssertionResult citation(Map<String, Object> expected, Object actual) {
        String documentId = text(expected.get("documentId"));
        boolean passed = actual != null && json(actual).contains(documentId);
        return new AssertionResult(passed, "Citations must reference document " + documentId);
    }

    private void refreshVersionGate(Long tenantId, Long versionId) {
        Integer missing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation_dataset dataset WHERE dataset.tenant_id=? AND dataset.status='active' " +
                        "AND NOT EXISTS (SELECT 1 FROM evaluation_run run WHERE run.dataset_id=dataset.id " +
                        "AND run.agent_version_id=? AND run.status='passed')",
                Integer.class, tenantId, versionId);
        versionService.markEvaluation(tenantId, versionId, missing != null && missing == 0);
    }

    private Map<String, Object> addCaseInternal(Long tenantId, Long datasetId, Map<String, Object> body) {
        requireDataset(tenantId, datasetId);
        String assertion = text(body.getOrDefault("assertionType", "contains"));
        if (!Set.of("contains", "not_contains", "exact", "regex", "json_contains", "citation").contains(assertion)) {
            throw new IllegalArgumentException("Unsupported assertionType");
        }
        Long id = jdbc.queryForObject(
                "INSERT INTO evaluation_case(dataset_id,name,input,expected,assertion_type,weight) " +
                        "VALUES (?,?,?::jsonb,?::jsonb,?,?) RETURNING id", Long.class, datasetId,
                required(body, "name"), json(body.getOrDefault("input", Map.of())),
                json(body.getOrDefault("expected", Map.of())), assertion,
                decimal(body.get("weight"), BigDecimal.ONE));
        return listCases(tenantId, datasetId).stream()
                .filter(item -> Objects.equals(((Number) item.get("id")).longValue(), id)).findFirst().orElseThrow();
    }

    private void requireDataset(Long tenantId, Long datasetId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM evaluation_dataset WHERE id=? AND tenant_id=?",
                Integer.class, datasetId, tenantId);
        if (count == null || count == 0) throw new NoSuchElementException("Evaluation dataset not found");
    }

    private String required(Map<String, Object> body, String key) {
        String value = text(body.get(key));
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    private BigDecimal decimal(Object value, BigDecimal fallback) {
        if (value instanceof BigDecimal decimal) return decimal;
        try { return new BigDecimal(String.valueOf(value)); } catch (Exception ignored) { return fallback; }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalArgumentException("Unable to serialize evaluation data", exception); }
    }

    private Object parse(Object value) {
        if (!(value instanceof String)) return value;
        try { return mapper.readValue((String) value, Object.class); }
        catch (Exception exception) { throw new IllegalArgumentException("Invalid evaluation JSON", exception); }
    }

    private Map<String, Object> asMap(Object value) {
        if (value == null) return Map.of();
        return mapper.convertValue(value, new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    record AssertionResult(boolean passed, String message) {}
}
