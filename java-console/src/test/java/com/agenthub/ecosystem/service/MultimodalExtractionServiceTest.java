package com.agenthub.ecosystem.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MultimodalExtractionServiceTest {
    private final MultimodalExtractionService service = new MultimodalExtractionService(new ObjectMapper());

    @Test
    void extractsDocumentStructureAndEntities() {
        byte[] content = "Contact ops@example.com or 13800138000\nPriority: high".getBytes(StandardCharsets.UTF_8);
        var result = service.extract("incident.txt", "text/plain", content, false);

        assertEquals("completed", result.status());
        assertEquals("document", result.extraction().get("kind"));
        assertEquals(1, ((java.util.List<?>) result.extraction().get("emails")).size());
        assertEquals(1, ((java.util.List<?>) result.extraction().get("phones")).size());
    }

    @Test
    void extractsImageDimensionsAndFlagsSemanticProviderRequirement() throws Exception {
        BufferedImage image = new BufferedImage(16, 9, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);

        var result = service.extract("sample.png", "image/png", output.toByteArray(), true);

        assertEquals("needs_provider", result.status());
        assertEquals(16, result.extraction().get("width"));
        assertEquals(9, result.extraction().get("height"));
        assertEquals("provider_required", result.extraction().get("semanticPhase"));
    }

    @Test
    void extractsWaveAudioMetadata() throws Exception {
        AudioFormat format = new AudioFormat(8000, 16, 1, true, false);
        byte[] samples = new byte[16000];
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (AudioInputStream audio = new AudioInputStream(new ByteArrayInputStream(samples), format, 8000)) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, output);
        }

        var result = service.extract("sample.wav", "audio/wav", output.toByteArray(), false);

        assertEquals("completed", result.status());
        assertEquals("audio", result.extraction().get("kind"));
        assertEquals(1.0, (Double) result.extraction().get("durationSeconds"), 0.01);
    }
}
