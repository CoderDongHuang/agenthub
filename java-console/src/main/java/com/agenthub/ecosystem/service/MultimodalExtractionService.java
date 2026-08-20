package com.agenthub.ecosystem.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioSystem;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MultimodalExtractionService {
    public static final int MAX_BYTES = 25 * 1024 * 1024;
    private static final Pattern EMAIL = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)");
    private final Tika tika = new Tika();
    private final ObjectMapper objectMapper;
    private final DashScopeMultimodalProvider semanticProvider;
    private final String ffmpegPath;

    @Autowired
    public MultimodalExtractionService(ObjectMapper objectMapper,
                                       DashScopeMultimodalProvider semanticProvider,
                                       @Value("${agenthub.multimodal.ffmpeg-path:ffmpeg}") String ffmpegPath) {
        this.objectMapper = objectMapper;
        this.semanticProvider = semanticProvider;
        this.ffmpegPath = resolveFfmpegPath(Objects.toString(ffmpegPath, "ffmpeg").trim());
    }

    MultimodalExtractionService(ObjectMapper objectMapper, DashScopeMultimodalProvider semanticProvider) {
        this(objectMapper, semanticProvider, "ffmpeg");
    }

    public ExtractionResult extract(String fileName, String declaredMediaType, byte[] content, boolean semanticRequested) {
        if (content == null || content.length == 0) throw new IllegalArgumentException("Media content is required");
        if (content.length > MAX_BYTES) throw new IllegalArgumentException("Media content exceeds the 25 MB limit");
        String safeName = safeFileName(fileName);
        String detected = tika.detect(content, safeName);
        String mediaType = detected == null || detected.isBlank() ? Objects.toString(declaredMediaType, "application/octet-stream") : detected;
        Map<String, Object> extraction;
        String pipeline;
        if (mediaType.startsWith("image/")) {
            extraction = image(content);
            pipeline = "image-metadata-v1";
        } else if (mediaType.startsWith("audio/")) {
            extraction = audio(content);
            pipeline = "audio-metadata-v1";
        } else if (mediaType.startsWith("video/")) {
            extraction = video(content);
            pipeline = "video-metadata-v1";
        } else {
            extraction = document(content, mediaType);
            pipeline = "document-structure-v1";
        }
        String status = "completed";
        String provider = null;
        boolean reviewRequired = false;
        if (semanticRequested) {
            extraction = new LinkedHashMap<>(extraction);
            try {
                String semanticFileName = safeName;
                String semanticMediaType = mediaType;
                byte[] semanticContent = content;
                if (mediaType.startsWith("video/")) {
                    semanticFileName = safeName + ".wav";
                    semanticMediaType = "audio/wav";
                    semanticContent = extractVideoAudio(content, safeName);
                    extraction.put("audioTrackExtracted", true);
                    extraction.put("audioTrackBytes", semanticContent.length);
                }
                DashScopeMultimodalProvider.SemanticResult semantic = semanticProvider.analyze(
                        semanticFileName, semanticMediaType, semanticContent);
                extraction.putAll(semantic.extraction());
                pipeline += (mediaType.startsWith("video/") ? "+video-audio-extract-v1" : "") + "+dashscope-semantic-v1";
                provider = semantic.provider();
                reviewRequired = true;
            } catch (DashScopeMultimodalProvider.ProviderNotConfiguredException exception) {
                status = "needs_provider";
                extraction.put("semanticPhase", "provider_required");
                extraction.put("providerRequirement", "Set DASHSCOPE_API_KEY to enable OCR, visual understanding and audio transcription");
            } catch (DashScopeMultimodalProvider.ProviderException exception) {
                status = "failed";
                provider = "alibaba-dashscope";
                reviewRequired = true;
                extraction.put("semanticPhase", "provider_error");
                extraction.put("providerError", exception.getMessage());
            }
        }
        return new ExtractionResult(safeName, mediaType, digest(content), content.length, pipeline,
                status, extraction, provider, reviewRequired);
    }

    private Map<String, Object> image(byte[] content) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
            if (image == null) throw new IllegalArgumentException("Image decoder could not read the payload");
            return Map.of(
                    "kind", "image",
                    "width", image.getWidth(),
                    "height", image.getHeight(),
                    "colorComponents", image.getColorModel().getNumColorComponents(),
                    "alpha", image.getColorModel().hasAlpha(),
                    "pixelCount", (long) image.getWidth() * image.getHeight()
            );
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to inspect image: " + exception.getMessage(), exception);
        }
    }

    private Map<String, Object> audio(byte[] content) {
        try (var stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(content))) {
            var format = stream.getFormat();
            double seconds = format.getFrameRate() <= 0 ? 0 : stream.getFrameLength() / format.getFrameRate();
            return Map.of(
                    "kind", "audio",
                    "durationSeconds", Math.round(seconds * 1000.0) / 1000.0,
                    "sampleRate", format.getSampleRate(),
                    "channels", format.getChannels(),
                    "sampleBits", format.getSampleSizeInBits(),
                    "encoding", format.getEncoding().toString()
            );
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to inspect audio: " + exception.getMessage(), exception);
        }
    }

    private Map<String, Object> video(byte[] content) {
        return Map.of("kind", "video", "bytes", content.length,
                "semanticInput", "audio_track", "audioTrackExtracted", false);
    }

    private byte[] extractVideoAudio(byte[] content, String fileName) {
        Path input = null;
        Path output = null;
        try {
            input = Files.createTempFile("agenthub-video-", extension(fileName, ".mp4"));
            output = Files.createTempFile("agenthub-audio-", ".wav");
            Files.write(input, content);
            Process process = new ProcessBuilder(ffmpegPath, "-hide_banner", "-loglevel", "error", "-y",
                    "-i", input.toString(), "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
                    output.toString()).redirectErrorStream(true).start();
            String diagnostics = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (process.waitFor() != 0) {
                throw new DashScopeMultimodalProvider.ProviderException("Unable to extract video audio with FFmpeg at " + ffmpegPath +
                        (diagnostics.isBlank() ? "" : ": " + diagnostics.substring(0, Math.min(300, diagnostics.length()))));
            }
            byte[] audio = Files.readAllBytes(output);
            if (audio.length == 0) throw new DashScopeMultimodalProvider.ProviderException("Video has no readable audio track");
            return audio;
        } catch (DashScopeMultimodalProvider.ProviderException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            // Clear the request worker's interrupt state so Spring can finish one response cleanly.
            throw new DashScopeMultimodalProvider.ProviderException("Unable to extract video audio with FFmpeg at " + ffmpegPath + ": process interrupted", exception);
        } catch (IOException exception) {
            throw new DashScopeMultimodalProvider.ProviderException("Unable to extract video audio with FFmpeg at " + ffmpegPath + ": " + exception.getMessage(), exception);
        } finally {
            deleteQuietly(input);
            deleteQuietly(output);
        }
    }

    private String extension(String fileName, String fallback) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 && dot < fileName.length() - 1 ? fileName.substring(dot) : fallback;
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }

    private String resolveFfmpegPath(String configured) {
        Path local = Path.of("D:\\PythonCode\\ffmpeg\\bin\\ffmpeg.exe");
        if (!configured.isBlank() && !configured.equalsIgnoreCase("ffmpeg")) {
            try {
                if (Files.isRegularFile(Path.of(configured))) return configured;
            } catch (InvalidPathException ignored) { }
        }
        return Files.isRegularFile(local) ? local.toString() : (configured.isBlank() ? "ffmpeg" : configured);
    }

    private Map<String, Object> document(byte[] content, String mediaType) {
        String text;
        try {
            if (mediaType.contains("json")) {
                Object parsed = objectMapper.readValue(content, Object.class);
                text = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsed);
            } else {
                AutoDetectParser parser = new AutoDetectParser();
                BodyContentHandler handler = new BodyContentHandler(-1);
                parser.parse(new ByteArrayInputStream(content), handler, new Metadata(), new ParseContext());
                text = handler.toString().replace("\u0000", "").trim();
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to extract document structure: " + exception.getMessage(), exception);
        }
        if (text.isBlank()) throw new IllegalArgumentException("No readable document content was found");
        List<String> emails = matches(EMAIL, text, 20);
        List<String> phones = matches(PHONE, text, 20);
        int lines = (int) text.lines().count();
        int words = text.trim().split("\\s+").length;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "document");
        result.put("characters", text.length());
        result.put("lines", lines);
        result.put("words", words);
        result.put("emails", emails);
        result.put("phones", phones);
        result.put("preview", text.substring(0, Math.min(text.length(), 2000)));
        return result;
    }

    private List<String> matches(Pattern pattern, String text, int limit) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find() && values.size() < limit) values.add(matcher.group());
        return List.copyOf(values);
    }

    private String safeFileName(String raw) {
        String name = Objects.toString(raw, "upload.bin").replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isBlank() || name.equals(".") || name.equals("..")) return "upload.bin";
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private String digest(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record ExtractionResult(String fileName, String mediaType, String digest, long bytes,
                                   String pipeline, String status, Map<String, Object> extraction,
                                   String provider, boolean reviewRequired) {}
}
