package com.agenthub.agent.controller;

import com.agenthub.agent.service.DocumentExtractionService;
import com.agenthub.common.response.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeBaseController {

    private final JdbcTemplate jdbc;
    private final DocumentExtractionService extractionService;

    public KnowledgeBaseController(JdbcTemplate jdbc, DocumentExtractionService extractionService) {
        this.jdbc = jdbc;
        this.extractionService = extractionService;
    }

    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "1") Long kbId) {
        try {
            DocumentExtractionService.ExtractedDocument document = extractionService.extract(file);
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
                "SELECT id, kb_id, filename, file_type, file_size, chunk_count, status, created_at " +
                        "FROM knowledge_document WHERE kb_id = ? ORDER BY created_at DESC",
                kbId
        ));
    }

    @GetMapping("/docs/chunks")
    public ApiResponse<List<Map<String, Object>>> listChunks() {
        return ApiResponse.ok(jdbc.queryForList(
                "SELECT doc_id, chunk_index, content FROM knowledge_chunk ORDER BY doc_id, chunk_index"
        ));
    }

    @GetMapping("/docs/{id}")
    public ApiResponse<Map<String, Object>> getDoc(@PathVariable Long id) {
        List<Map<String, Object>> docs = jdbc.queryForList("SELECT * FROM knowledge_document WHERE id = ?", id);
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
        int affected = jdbc.update("DELETE FROM knowledge_document WHERE id = ?", id);
        return affected == 0 ? ApiResponse.error(404, "Document not found") : ApiResponse.ok("Document deleted");
    }
}
