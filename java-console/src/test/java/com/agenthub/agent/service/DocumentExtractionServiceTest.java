package com.agenthub.agent.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentExtractionServiceTest {

    private final DocumentExtractionService service = new DocumentExtractionService();

    @Test
    void extractsPlainText() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "guide.md", "text/markdown", "# AgentHub\nKnowledge guide".getBytes(StandardCharsets.UTF_8)
        );

        DocumentExtractionService.ExtractedDocument document = service.extract(file);

        assertEquals("md", document.fileType());
        assertTrue(document.content().contains("Knowledge guide"));
    }

    @Test
    void extractsDocx() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "guide.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                createDocx("AgentHub DOCX knowledge")
        );

        DocumentExtractionService.ExtractedDocument document = service.extract(file);

        assertEquals("docx", document.fileType());
        assertTrue(document.content().contains("AgentHub DOCX knowledge"));
    }

    @Test
    void extractsPdf() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "guide.pdf", "application/pdf", createPdf("AgentHub PDF knowledge")
        );

        DocumentExtractionService.ExtractedDocument document = service.extract(file);

        assertEquals("pdf", document.fileType());
        assertTrue(document.content().contains("AgentHub PDF knowledge"));
    }

    @Test
    void rejectsUnsupportedFiles() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "archive.exe", "application/octet-stream", new byte[]{1, 2, 3}
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service.extract(file));
        assertTrue(exception.getMessage().contains("Unsupported file type"));
    }

    private byte[] createDocx(String text) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            addZipEntry(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """);
            addZipEntry(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                    </Relationships>
                    """);
            addZipEntry(zip, "word/document.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body><w:p><w:r><w:t>%s</w:t></w:r></w:p></w:body>
                    </w:document>
                    """.formatted(text));
        }
        return output.toByteArray();
    }

    private byte[] createPdf(String text) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText(text);
                content.endText();
            }
            document.save(output);
        }
        return output.toByteArray();
    }

    private void addZipEntry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
