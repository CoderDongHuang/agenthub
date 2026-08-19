package com.agenthub.ecosystem.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 阿里云百炼 DashScope 多模态适配器。视觉和音频均走国内 OpenAI 兼容接口，
 * 一个 DASHSCOPE_API_KEY 即可覆盖 OCR、视觉理解和语音转写。
 */
@Component
public class DashScopeMultimodalProvider {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final int MAX_INLINE_BYTES = 18 * 1024 * 1024;
    private final ObjectMapper objectMapper;
    private final RestClient client;
    private final String apiKey;
    private final String visionModel;
    private final String audioModel;

    public DashScopeMultimodalProvider(ObjectMapper objectMapper,
                                       @Value("${agenthub.multimodal.dashscope.api-key:}") String apiKey,
                                       @Value("${agenthub.multimodal.dashscope.vision-model:qwen-vl-plus}") String visionModel,
                                       @Value("${agenthub.multimodal.dashscope.audio-model:qwen-audio-turbo}") String audioModel,
                                       @Value("${agenthub.multimodal.dashscope.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl) {
        this.objectMapper = objectMapper;
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.apiKey = Objects.toString(apiKey, "").trim();
        this.visionModel = Objects.toString(visionModel, "qwen-vl-plus").trim();
        this.audioModel = Objects.toString(audioModel, "qwen-audio-turbo").trim();
    }

    DashScopeMultimodalProvider(ObjectMapper objectMapper, RestClient client, String apiKey,
                                String visionModel, String audioModel) {
        this.objectMapper = objectMapper;
        this.client = client;
        this.apiKey = Objects.toString(apiKey, "").trim();
        this.visionModel = Objects.toString(visionModel, "qwen-vl-plus").trim();
        this.audioModel = Objects.toString(audioModel, "qwen-audio-turbo").trim();
    }

    public boolean configured() { return !apiKey.isBlank(); }

    public SemanticResult analyze(String fileName, String mediaType, byte[] content) {
        if (!configured()) throw new ProviderNotConfiguredException();
        if (content.length > MAX_INLINE_BYTES) {
            throw new ProviderException("DashScope inline media is limited to 18 MB; compress or split the file");
        }
        String dataUrl = "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(content);
        String prompt = "Return only valid JSON for the attached file named " + fileName + ". "
                + "Include language, confidence from 0 to 1, and warnings as an array. ";
        Map<String, Object> contentPart;
        if (mediaType.startsWith("audio/")) {
            String format = mediaType.substring("audio/".length());
            contentPart = Map.of("type", "input_audio", "input_audio", Map.of("data", dataUrl, "format", format));
            prompt += "Transcribe all audible speech faithfully into transcription and return speakers as an array.";
        } else if (mediaType.startsWith("image/")) {
            contentPart = Map.of("type", "image_url", "image_url", Map.of("url", dataUrl));
            prompt += "Perform faithful OCR into ocrText, describe visible content in visualDescription, and list notable objects in objects.";
        } else {
            throw new ProviderException("DashScope semantic extraction supports image and audio media only");
        }
        String model = mediaType.startsWith("audio/") ? audioModel : visionModel;
        Map<String, Object> request = Map.of("model", model, "temperature", 0.1,
                "messages", List.of(Map.of("role", "user", "content", List.of(
                        Map.of("type", "text", "text", prompt), contentPart))));
        try {
            Map<?, ?> response = client.post().uri("/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(Map.class);
            Map<String, Object> semantic = parseJson(messageText(response));
            semantic.put("provider", "alibaba-dashscope");
            semantic.put("model", model);
            semantic.put("semanticPhase", "completed");
            return new SemanticResult("alibaba-dashscope", model, semantic);
        } catch (ProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ProviderException("DashScope multimodal request failed", exception);
        }
    }

    private String messageText(Map<?, ?> response) {
        try {
            List<?> choices = (List<?>) response.get("choices");
            Map<?, ?> choice = (Map<?, ?>) choices.getFirst();
            Map<?, ?> message = (Map<?, ?>) choice.get("message");
            Object content = message.get("content");
            String text = content instanceof String ? (String) content : objectMapper.writeValueAsString(content);
            if (text.isBlank()) throw new ProviderException("DashScope returned no semantic result");
            return text;
        } catch (ProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ProviderException("DashScope response format is invalid", exception);
        }
    }

    private Map<String, Object> parseJson(String text) {
        String normalized = text.trim();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        try {
            return new LinkedHashMap<>(objectMapper.readValue(normalized, MAP_TYPE));
        } catch (Exception exception) {
            throw new ProviderException("DashScope returned invalid JSON", exception);
        }
    }

    public record SemanticResult(String provider, String model, Map<String, Object> extraction) {}

    public static class ProviderNotConfiguredException extends RuntimeException {
        public ProviderNotConfiguredException() { super("DashScope API key is not configured"); }
    }

    public static class ProviderException extends RuntimeException {
        public ProviderException(String message) { super(message); }
        public ProviderException(String message, Throwable cause) { super(message, cause); }
    }
}
