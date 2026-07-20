package com.agenthub.agent.service;

import com.agenthub.agent.dto.AgentCreateRequest;
import com.agenthub.agent.entity.AgentDefinition;
import com.agenthub.agent.repository.AgentDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    @Mock AgentDefinitionRepository agentRepository;
    @InjectMocks AgentService agentService;

    @Test
    void create_shouldReturnDraftAgent() {
        AgentCreateRequest req = new AgentCreateRequest();
        req.setName("Test");
        req.setSystemPrompt("You are helpful");
        req.setModel("deepseek-v3");

        when(agentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        AgentDefinition agent = agentService.create(req, 1L);
        assertEquals("Test", agent.getName());
        assertEquals("draft", agent.getStatus());
        assertEquals("deepseek-v3", agent.getModel());
    }

    @Test
    void publish_shouldSetStatus() {
        AgentDefinition agent = AgentDefinition.builder().id(1L).name("T").status("draft")
                .systemPrompt("p").model("m").build();
        when(agentRepository.findById(1L)).thenReturn(Optional.of(agent));
        when(agentRepository.save(any())).thenReturn(agent);

        AgentDefinition result = agentService.publish(1L);
        assertEquals("published", result.getStatus());
    }

    @Test
    void disable_shouldSetStatus() {
        AgentDefinition agent = AgentDefinition.builder().id(1L).name("T").status("published")
                .systemPrompt("p").model("m").build();
        when(agentRepository.findById(1L)).thenReturn(Optional.of(agent));
        when(agentRepository.save(any())).thenReturn(agent);

        AgentDefinition result = agentService.disable(1L);
        assertEquals("disabled", result.getStatus());
    }

    @Test
    void get_shouldThrowWhenNotFound() {
        when(agentRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> agentService.get(99L));
    }
}
