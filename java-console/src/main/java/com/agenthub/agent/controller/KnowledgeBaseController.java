package com.agenthub.agent.controller;

import com.agenthub.common.response.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeBaseController {

    private final JdbcTemplate jdbc;

    public KnowledgeBaseController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "1") Long kbId) {
        try {
            String filename = file.getOriginalFilename();
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            String fileType = getFileType(filename);
            long fileSize = file.getSize();

            jdbc.update(
                    "INSERT INTO knowledge_document (kb_id, filename, file_type, file_size, content, status) VALUES (?,?,?,?,?,'indexed')",
                    kbId, filename, fileType, fileSize, content
            );

            return ApiResponse.ok(Map.of("filename", filename, "size", fileSize, "status", "indexed"));
        } catch (Exception e) {
            return ApiResponse.error("Upload failed: " + e.getMessage());
        }
    }

    @PostMapping("/text")
    public ApiResponse<Map<String, Object>> addText(@RequestBody Map<String, String> body) {
        String title = body.getOrDefault("title", "Untitled");
        String content = body.getOrDefault("content", "");
        Long kbId = Long.valueOf(body.getOrDefault("kbId", "1"));

        jdbc.update(
                "INSERT INTO knowledge_document (kb_id, filename, file_type, file_size, content, status) VALUES (?,?,?,?,?,'active')",
                kbId, title + ".txt", "txt", (long) content.length(), content
        );

        return ApiResponse.ok(Map.of("title", title, "status", "active"));
    }

    @GetMapping("/docs")
    public ApiResponse<List<Map<String, Object>>> listDocs(@RequestParam(defaultValue = "1") Long kbId) {
        List<Map<String, Object>> docs = jdbc.queryForList(
                "SELECT id, kb_id, filename, file_type, file_size, chunk_count, status, created_at FROM knowledge_document WHERE kb_id = ? ORDER BY created_at DESC",
                kbId
        );
        return ApiResponse.ok(docs);
    }

    @DeleteMapping("/docs/{id}")
    public ApiResponse<String> deleteDoc(@PathVariable Long id) {
        jdbc.update("DELETE FROM knowledge_document WHERE id = ?", id);
        return ApiResponse.ok("Document deleted");
    }

    @GetMapping("/docs/{id}")
    public ApiResponse<Map<String, Object>> getDoc(@PathVariable Long id) {
        Map<String, Object> doc = jdbc.queryForMap(
                "SELECT * FROM knowledge_document WHERE id = ?", id
        );
        return ApiResponse.ok(doc);
    }

    private String getFileType(String filename) {
        if (filename == null) return "txt";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".docx") || lower.endsWith(".doc")) return "doc";
        if (lower.endsWith(".md")) return "md";
        if (lower.endsWith(".txt")) return "txt";
        return "other";
    }
}
