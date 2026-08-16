package com.agenthub.knowledge.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class KnowledgeEvolutionService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public KnowledgeEvolutionService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<Map<String, Object>> listSources(Long tenantId, Long kbId) {
        requireKnowledgeBase(tenantId, kbId);
        return jdbc.queryForList(
                "SELECT id,kb_id,source_key,source_type,config::text AS config,inherited_acl::text AS inherited_acl," +
                        "sync_cursor,status,last_sync_at,last_error,created_at,updated_at FROM knowledge_source " +
                        "WHERE tenant_id=? AND kb_id=? ORDER BY updated_at DESC", tenantId, kbId).stream()
                .map(this::normalizeSource).toList();
    }

    public Map<String, Object> createSource(Long tenantId, Long kbId, Map<String, Object> body) {
        requireKnowledgeBase(tenantId, kbId);
        String key = required(body, "sourceKey");
        String type = required(body, "sourceType");
        Long id = jdbc.queryForObject(
                "INSERT INTO knowledge_source(tenant_id,kb_id,source_key,source_type,config,inherited_acl) " +
                        "VALUES (?,?,?,?,?::jsonb,?::jsonb) RETURNING id", Long.class, tenantId, kbId, key, type,
                json(body.getOrDefault("config", Map.of())), json(body.getOrDefault("inheritedAcl", Map.of())));
        return getSource(tenantId, id);
    }

    @Transactional
    public Map<String, Object> sync(Long tenantId, Long sourceId, Map<String, Object> body) {
        Map<String, Object> source = getSource(tenantId, sourceId);
        Object rawDocuments = body.get("documents");
        if (!(rawDocuments instanceof List<?> documents)) {
            throw new IllegalArgumentException("documents must be an array");
        }
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        List<Long> changedDocumentIds = new ArrayList<>();
        for (Object raw : documents) {
            Map<String, Object> document = asMap(raw);
            String externalId = required(document, "externalId");
            String title = required(document, "title");
            String content = required(document, "content");
            String hash = sha256(content);
            List<Map<String, Object>> existing = jdbc.queryForList(
                    "SELECT id,content_hash,source_version FROM knowledge_document WHERE source_id=? AND external_id=?",
                    sourceId, externalId);
            Object acl = document.containsKey("acl") ? document.get("acl") : source.get("inherited_acl");
            if (existing.isEmpty()) {
                Long id = jdbc.queryForObject(
                        "INSERT INTO knowledge_document(kb_id,filename,file_type,file_size,content,status,source_id," +
                                "external_id,content_hash,source_version,inherited_acl) " +
                                "VALUES (?,?,?,?,?,'uploaded',?,?,?,1,?::jsonb) RETURNING id",
                        Long.class, source.get("kb_id"), title, text(document.getOrDefault("fileType", "text")),
                        content.getBytes(StandardCharsets.UTF_8).length, content, sourceId, externalId, hash, json(acl));
                changedDocumentIds.add(id);
                created++;
            } else if (Objects.equals(hash, existing.get(0).get("content_hash"))) {
                unchanged++;
            } else {
                Long id = ((Number) existing.get(0).get("id")).longValue();
                int version = ((Number) existing.get(0).get("source_version")).intValue() + 1;
                jdbc.update("UPDATE knowledge_document SET filename=?,file_type=?,file_size=?,content=?,status='uploaded'," +
                                "chunk_count=0,content_hash=?,source_version=?,inherited_acl=?::jsonb WHERE id=?",
                        title, text(document.getOrDefault("fileType", "text")),
                        content.getBytes(StandardCharsets.UTF_8).length, content, hash, version, json(acl), id);
                jdbc.update("DELETE FROM knowledge_chunk WHERE doc_id=?", id);
                changedDocumentIds.add(id);
                updated++;
            }
        }
        String cursor = text(body.get("cursor"));
        jdbc.update("UPDATE knowledge_source SET sync_cursor=?,last_sync_at=NOW(),last_error=NULL,updated_at=NOW() WHERE id=?",
                cursor.isBlank() ? null : cursor, sourceId);
        return Map.of("sourceId", sourceId, "created", created, "updated", updated, "unchanged", unchanged,
                "changedDocumentIds", changedDocumentIds, "cursor", cursor);
    }

    public Map<String, Object> evaluate(Long tenantId, Long kbId, Map<String, Object> body) {
        requireKnowledgeBase(tenantId, kbId);
        String query = required(body, "query");
        Long expectedDocumentId = longValue(body.get("expectedDocumentId"));
        String[] terms = Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(term -> term.length() >= 2).distinct().limit(8).toArray(String[]::new);
        if (terms.length == 0) terms = new String[]{query.toLowerCase(Locale.ROOT)};
        List<Map<String, Object>> chunks = jdbc.queryForList(
                "SELECT chunk.doc_id,chunk.chunk_index,chunk.content,chunk.citation::text AS citation,doc.filename " +
                        "FROM knowledge_chunk chunk JOIN knowledge_document doc ON doc.id=chunk.doc_id " +
                        "JOIN knowledge_base kb ON kb.id=doc.kb_id WHERE kb.id=? AND kb.tenant_id=?",
                kbId, tenantId);
        Map<String, Object> best = null;
        double bestScore = 0;
        for (Map<String, Object> chunk : chunks) {
            String content = text(chunk.get("content")).toLowerCase(Locale.ROOT);
            int matched = 0;
            for (String term : terms) if (content.contains(term)) matched++;
            double score = terms.length == 0 ? 0 : (double) matched / terms.length;
            if (score > bestScore) {
                bestScore = score;
                best = chunk;
            }
        }
        Long matchedDocumentId = best == null ? null : ((Number) best.get("doc_id")).longValue();
        boolean passed = expectedDocumentId == null ? bestScore > 0 : Objects.equals(expectedDocumentId, matchedDocumentId);
        List<Map<String, Object>> citations = best == null ? List.of() : List.of(Map.of(
                "documentId", matchedDocumentId, "filename", best.get("filename"),
                "chunkIndex", best.get("chunk_index"), "score", bestScore));
        Long runId = jdbc.queryForObject(
                "INSERT INTO knowledge_evaluation_run(tenant_id,kb_id,query,expected_document_id,matched_document_id," +
                        "score,passed,citations) VALUES (?,?,?,?,?,?,?,?::jsonb) RETURNING id",
                Long.class, tenantId, kbId, query, expectedDocumentId, matchedDocumentId, bestScore, passed, json(citations));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", runId);
        result.put("query", query);
        result.put("passed", passed);
        result.put("score", bestScore);
        result.put("matchedDocumentId", matchedDocumentId);
        result.put("citations", citations);
        return result;
    }

    public List<Map<String, Object>> evaluationRuns(Long tenantId, Long kbId) {
        requireKnowledgeBase(tenantId, kbId);
        return jdbc.queryForList(
                "SELECT id,query,expected_document_id,matched_document_id,score,passed,citations::text AS citations,created_at " +
                        "FROM knowledge_evaluation_run WHERE tenant_id=? AND kb_id=? ORDER BY created_at DESC LIMIT 50",
                tenantId, kbId).stream().map(row -> {
                    Map<String, Object> item = new LinkedHashMap<>(row);
                    item.put("citations", parse(row.get("citations")));
                    return item;
                }).toList();
    }

    private Map<String, Object> getSource(Long tenantId, Long sourceId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,kb_id,source_key,source_type,config::text AS config,inherited_acl::text AS inherited_acl," +
                        "sync_cursor,status,last_sync_at,last_error,created_at,updated_at FROM knowledge_source " +
                        "WHERE tenant_id=? AND id=?", tenantId, sourceId);
        if (rows.isEmpty()) throw new NoSuchElementException("Knowledge source not found");
        return normalizeSource(rows.get(0));
    }

    private Map<String, Object> normalizeSource(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("config", parse(row.get("config")));
        result.put("inherited_acl", parse(row.get("inherited_acl")));
        return result;
    }

    private void requireKnowledgeBase(Long tenantId, Long kbId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_base WHERE id=? AND tenant_id=?",
                Integer.class, kbId, tenantId);
        if (count == null || count == 0) throw new NoSuchElementException("Knowledge base not found");
    }

    private String required(Map<String, Object> body, String key) {
        String value = text(body.get(key));
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    private Long longValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        if (value instanceof Number number) return number.longValue();
        return Long.valueOf(String.valueOf(value));
    }

    private Map<String, Object> asMap(Object value) {
        if (value == null) return Map.of();
        return mapper.convertValue(value, new TypeReference<>() {});
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception exception) { throw new IllegalArgumentException("Unable to serialize knowledge data", exception); }
    }

    private Object parse(Object value) {
        if (!(value instanceof String text)) return value;
        try { return mapper.readValue(text, Object.class); }
        catch (Exception exception) { return Map.of(); }
    }

    private String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
