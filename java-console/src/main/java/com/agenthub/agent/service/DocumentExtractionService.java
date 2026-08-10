package com.agenthub.agent.service;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Locale;
import java.util.Set;

@Service
public class DocumentExtractionService {

    public static final long MAX_FILE_SIZE = 25L * 1024 * 1024;
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "txt", "md", "pdf", "docx", "xlsx", "pptx", "csv", "json", "html", "htm", "xml", "yaml", "yml"
    );

    public ExtractedDocument extract(MultipartFile file) {
        String filename = file.getOriginalFilename() == null ? "document.txt" : file.getOriginalFilename();
        String extension = extensionOf(filename);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Unsupported file type: ." + extension);
        }
        if (file.isEmpty()) {
            throw new IllegalArgumentException("The uploaded file is empty");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File exceeds the 25 MB limit");
        }

        try (InputStream input = file.getInputStream()) {
            AutoDetectParser parser = new AutoDetectParser();
            BodyContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            parser.parse(input, handler, metadata, new ParseContext());
            String content = handler.toString().replace("\u0000", "").trim();
            if (content.isBlank()) {
                throw new IllegalArgumentException("No readable text was found in the document");
            }
            return new ExtractedDocument(filename, extension, file.getSize(), content);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to parse document: " + exception.getMessage(), exception);
        }
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "txt" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public record ExtractedDocument(String filename, String fileType, long fileSize, String content) {}
}
