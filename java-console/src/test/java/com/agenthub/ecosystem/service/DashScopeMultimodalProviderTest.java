package com.agenthub.ecosystem.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DashScopeMultimodalProviderTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsVisionRequestAndParsesStructuredResult() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dashscope.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DashScopeMultimodalProvider provider = new DashScopeMultimodalProvider(
                objectMapper, builder.build(), "test-key", "qwen-vl-plus", "qwen-audio-turbo");
        server.expect(requestTo("https://dashscope.test/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("image_url")))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"{\\"language\\":\\"zh\\",\\"ocrText\\":\\"TOTAL 42\\",\\"visualDescription\\":\\"receipt\\",\\"objects\\":[\\"receipt\\"],\\"confidence\\":0.98,\\"warnings\\":[]}"}}]}
                        """, MediaType.APPLICATION_JSON));

        var result = provider.analyze("receipt.png", "image/png", "png".getBytes(StandardCharsets.UTF_8));

        assertEquals("alibaba-dashscope", result.provider());
        assertEquals("TOTAL 42", result.extraction().get("ocrText"));
        assertEquals("completed", result.extraction().get("semanticPhase"));
        server.verify();
    }

    @Test
    void rejectsRequestsWhenNoKeyIsConfigured() {
        DashScopeMultimodalProvider provider = new DashScopeMultimodalProvider(
                objectMapper, RestClient.create(), "", "qwen-vl-plus", "qwen-audio-turbo");

        assertFalse(provider.configured());
        assertThrows(DashScopeMultimodalProvider.ProviderNotConfiguredException.class,
                () -> provider.analyze("sample.wav", "audio/wav", new byte[]{1}));
    }

    @Test
    void convertsInvalidProviderPayloadIntoSafeError() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dashscope.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DashScopeMultimodalProvider provider = new DashScopeMultimodalProvider(
                objectMapper, builder.build(), "test-key", "qwen-vl-plus", "qwen-audio-turbo");
        server.expect(anything()).andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        var error = assertThrows(DashScopeMultimodalProvider.ProviderException.class,
                () -> provider.analyze("sample.wav", "audio/wav", new byte[]{1}));

        assertEquals("DashScope response format is invalid", error.getMessage());
        assertFalse(error.getMessage().contains("test-key"));
    }
}
