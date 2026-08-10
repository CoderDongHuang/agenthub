package com.agenthub.agent.controller;

import com.agenthub.agent.service.DocumentExtractionService;
import com.agenthub.common.response.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import com.agenthub.common.config.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeBaseController {

    private final JdbcTemplate jdbc;
    private final DocumentExtractionService extractionService;
    private final RestClient runtimeClient;
    private final String internalToken;

    public KnowledgeBaseController(JdbcTemplate jdbc, DocumentExtractionService extractionService,
                                   @Value("${python.runtime.base-url}") String runtimeBaseUrl,
                                   @Value("${agenthub.internal-token}") String internalToken) {
        this.jdbc = jdbc;
        this.extractionService = extractionService;
        this.runtimeClient = RestClient.builder().baseUrl(runtimeBaseUrl).build();
        this.internalToken = internalToken;
    }

    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "1") Long kbId) {
        try {
            DocumentExtractionService.ExtractedDocument document = extractionService.extract(file);
            requireKnowledgeBase(kbId);
            Long id = jdbc.queryForObject(
                    "INSERT INTO knowledge_document (kb_id, filename, file_type, file_size, content, status) " +
                            "VALUES (?,?,?,?,?,'uploaded') RETURNING id",
                    Long.class,
                    kbId, document.filename(), document.fileType(), document.fileSize(), document.content()
            );
            return ApiResponse.ok(Map.of(
                    "id", id,
                    "filename", document.filename(),
                    "fileType", document.fileType(),
                    "size", document.fileSize(),
                    "characters", document.content().length(),
                    "status", "uploaded"
            ));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.error(400, exception.getMessage());
        } catch (Exception exception) {
            return ApiResponse.error("Upload failed: " + exception.getMessage());
        }
    }

    @PostMapping("/text")
    public ApiResponse<Map<String, Object>> addText(@RequestBody Map<String, String> body) {
        String title = body.getOrDefault("title", "Untitled").trim();
        String content = body.getOrDefault("content", "").trim();
        Long kbId = Long.valueOf(body.getOrDefault("kbId", "1"));
        if (content.isBlank()) {
            return ApiResponse.error(400, "Content is required");
        }
        requireKnowledgeBase(kbId);

        Long id = jdbc.queryForObject(
                "INSERT INTO knowledge_document (kb_id, filename, file_type, file_size, content, status) " +
                        "VALUES (?,?,?,?,?,'uploaded') RETURNING id",
                Long.class,
                kbId, title + ".txt", "txt", (long) content.getBytes().length, content
        );
        return ApiResponse.ok(Map.of("id", id, "title", title, "status", "uploaded"));
    }

    @GetMapping("/docs")
    public ApiResponse<List<Map<String, Object>>> listDocs(@RequestParam(defaultValue = "1") Long kbId) {
        return ApiResponse.ok(jdbc.queryForList(
                "SELECT doc.id, doc.kb_id, doc.filename, doc.file_type, doc.file_size, doc.chunk_count, " +
                        "doc.status, doc.created_at " +
                "FROM knowledge_document doc JOIN knowledge_base kb ON kb.id = doc.kb_id " +
                        "WHERE doc.kb_id = ? AND kb.tenant_id = ? ORDER BY doc.created_at DESC",
                kbId, tenantId()
        ));
    }

    @GetMapping("/docs/chunks")
    public ApiResponse<List<Map<String, Object>>> listChunks() {
        return ApiResponse.ok(jdbc.queryForList(
                "SELECT kb.tenant_id, chunk.doc_id, chunk.chunk_index, chunk.content FROM knowledge_chunk chunk " +
                        "JOIN knowledge_document doc ON doc.id = chunk.doc_id JOIN knowledge_base kb ON kb.id = doc.kb_id " +
                        "WHERE kb.tenant_id = ? ORDER BY chunk.doc_id, chunk.chunk_index", tenantId()
        ));
    }

    @GetMapping("/docs/{id}")
    public ApiResponse<Map<String, Object>> getDoc(@PathVariable Long id) {
        List<Map<String, Object>> docs = jdbc.queryForList(
                "SELECT doc.* FROM knowledge_document doc JOIN knowledge_base kb ON kb.id = doc.kb_id " +
                        "WHERE doc.id = ? AND kb.tenant_id = ?", id, tenantId());
        return docs.isEmpty() ? ApiResponse.error(404, "Document not found") : ApiResponse.ok(docs.get(0));
    }

    @PostMapping("/docs/{id}/chunks")
    @Transactional
    public ApiResponse<Map<String, Object>> replaceChunks(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        Object rawChunks = body.get("chunks");
        if (!(rawChunks instanceof List<?> chunks)) {
            return ApiResponse.error(400, "chunks must be an array");
        }
        requireDocument(id);
        jdbc.update("DELETE FROM knowledge_chunk WHERE doc_id = ?", id);
        int index = 0;
        for (Object chunk : chunks) {
            String content = String.valueOf(chunk).trim();
            if (!content.isBlank()) {
                jdbc.update(
                        "INSERT INTO knowledge_chunk (doc_id, chunk_index, content) VALUES (?,?,?)",
                        id, index++, content
                );
            }
        }
        jdbc.update(
                "UPDATE knowledge_document SET chunk_count = ?, status = 'indexed' WHERE id = ?",
                index, id
        );
        return ApiResponse.ok(Map.of("documentId", id, "chunks", index, "status", "indexed"));
    }

    @DeleteMapping("/docs/{id}")
    public ApiResponse<String> deleteDoc(@PathVariable Long id) {
        requireDocument(id);
        runtimeClient.delete().uri("/rag/docs/{id}?tenant_id={tenantId}", id, tenantId())
                .header("X-Internal-Token", internalToken).retrieve().toBodilessEntity();
        int affected = jdbc.update("DELETE FROM knowledge_document WHERE id = ?", id);
        return affected == 0 ? ApiResponse.error(404, "Document not found") : ApiResponse.ok("Document deleted");
    }

    @PostMapping("/docs/{id}/index")
    public ApiResponse<Map<String, Object>> indexDocument(@PathVariable Long id) {
        requireDocument(id);
        @SuppressWarnings("unchecked")
        Map<String, Object> response = runtimeClient.post()
                .uri("/rag/index?doc_id={id}&tenant_id={tenantId}", id, tenantId())
                .header("X-Internal-Token", internalToken).retrieve().body(Map.class);
        return ApiResponse.ok(response == null ? Map.of() : response);
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        @SuppressWarnings("unchecked")
        Map<String, Object> response = runtimeClient.get()
                .uri("/rag/stats?tenant_id={tenantId}", tenantId())
                .header("X-Internal-Token", internalToken).retrieve().body(Map.class);
        return ApiResponse.ok(response == null ? Map.of() : response);
    }

    private void requireKnowledgeBase(Long kbId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_base WHERE id = ? AND tenant_id = ?",
                Integer.class, kbId, tenantId());
        if (count == null || count == 0) throw new IllegalArgumentException("Knowledge base not found");
    }

    private void requireDocument(Long documentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_document doc JOIN knowledge_base kb ON kb.id = doc.kb_id " +
                        "WHERE doc.id = ? AND kb.tenant_id = ?", Integer.class, documentId, tenantId());
        if (count == null || count == 0) throw new IllegalArgumentException("Document not found");
    }

    private Long tenantId() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) throw new IllegalStateException("Tenant context is required");
        return tenantId;
    }
}
