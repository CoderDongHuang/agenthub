package com.agenthub.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class InternalAuthenticationFilterTest {
    private static final String TOKEN = "test-internal-token-with-more-than-32-characters";

    @Test
    void rejectsMissingCredentials() throws Exception {
        InternalAuthenticationFilter filter = new InternalAuthenticationFilter(TOKEN);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/tools/register");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("Filter chain must not run"));
        assertEquals(401, response.getStatus());
    }

    @Test
    void authenticatesTrustedRuntimeWithTenant() throws Exception {
        InternalAuthenticationFilter filter = new InternalAuthenticationFilter(TOKEN);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/tools/register");
        request.addHeader("X-Internal-Token", TOKEN);
        request.addHeader("X-Tenant-Id", "9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        FilterChain chain = (req, res) -> {
            invoked.set(true);
            AuthenticatedUser user = (AuthenticatedUser) org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication().getPrincipal();
            assertEquals(9L, user.tenantId());
        };
        filter.doFilter(request, response, chain);
        assertTrue(invoked.get());
        assertNull(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void refusesWeakConfiguredSecret() {
        assertThrows(IllegalStateException.class, () -> new InternalAuthenticationFilter("too-short"));
    }
}
