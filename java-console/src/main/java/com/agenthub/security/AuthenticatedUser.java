package com.agenthub.security;

import java.security.Principal;

public record AuthenticatedUser(Long userId, Long tenantId, String username) implements Principal {
    @Override
    public String getName() {
        return username;
    }
}
