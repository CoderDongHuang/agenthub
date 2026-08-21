package com.agenthub.common.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;

@Component
public class ProductionConfigurationValidator implements ApplicationRunner {
    private final Environment environment;

    public ProductionConfigurationValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean production = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> profile.equalsIgnoreCase("prod") || profile.equalsIgnoreCase("production"));
        if (!production) return;

        requireStrongSecret("JWT_SECRET");
        requireStrongSecret("AGENTHUB_INTERNAL_TOKEN");
        requireStrongSecret("AGENTHUB_KMS_MASTER_KEY");
        requireStrongSecret("AGENTHUB_ECOSYSTEM_SIGNING_KEY");
        if (!environment.getProperty("AGENTHUB_SECURE_COOKIE", Boolean.class, false)) {
            fail("AGENTHUB_SECURE_COOKIE must be true");
        }
        String origins = environment.getProperty("AGENTHUB_CORS_ORIGINS", "").toLowerCase(Locale.ROOT);
        if (origins.isBlank() || origins.contains("localhost") || origins.contains("127.0.0.1") || origins.contains("*")) {
            fail("AGENTHUB_CORS_ORIGINS must be an explicit production allowlist");
        }
        String kms = environment.getProperty("AGENTHUB_KMS_MASTER_KEY", "");
        String jwt = environment.getProperty("JWT_SECRET", "");
        String signing = environment.getProperty("AGENTHUB_ECOSYSTEM_SIGNING_KEY", "");
        if (kms.equals(jwt) || signing.equals(jwt) || signing.equals(kms)) {
            fail("JWT, KMS, and ecosystem signing keys must be independent");
        }
    }

    private void requireStrongSecret(String name) {
        String value = environment.getProperty(name, "");
        String normalized = value.toLowerCase(Locale.ROOT);
        if (value.length() < 32 || normalized.contains("change_me") || normalized.contains("replace-with")
                || normalized.contains("your-") || normalized.contains("example")) {
            fail(name + " must be a non-placeholder secret of at least 32 characters");
        }
    }

    private static void fail(String message) {
        throw new IllegalStateException("Unsafe production configuration: " + message);
    }
}
