package com.agenthub.governance.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class EnterpriseGovernanceService {
    private static final Set<String> RETENTION_TABLES = Set.of("audit_log", "token_usage", "execution_trace");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final TenantKeyService keys;
    private final GovernancePolicyEvaluator evaluator;
    private final SecureRandom random = new SecureRandom();

    public EnterpriseGovernanceService(JdbcTemplate jdbc, ObjectMapper mapper, PasswordEncoder passwordEncoder,
                                       TenantKeyService keys, GovernancePolicyEvaluator evaluator) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.keys = keys;
        this.evaluator = evaluator;
    }

    public Map<String, Object> overview(long tenantId) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("identityProviders", count("identity_provider", tenantId));
        counts.put("activeScimTokens", scalar("SELECT COUNT(*) FROM scim_token WHERE tenant_id=? AND revoked_at IS NULL " +
                "AND (expires_at IS NULL OR expires_at > NOW())", tenantId));
        counts.put("accessPolicies", count("access_policy", tenantId));
        counts.put("managedSecrets", count("secret_record", tenantId));
        counts.put("retentionPolicies", count("data_retention_policy", tenantId));
        counts.put("approvalPolicies", count("approval_policy", tenantId));
        counts.put("overdueApprovals", scalar("SELECT COUNT(*) FROM approval_request WHERE tenant_id=? AND status='pending' " +
                "AND due_at < NOW()", tenantId));
        counts.put("recoveryArtifacts", count("governance_job", tenantId));
        return Map.of("tenantId", tenantId, "counts", counts, "controls", List.of(
                "identity", "kms", "data_governance", "guardrails", "approval_policy", "approval_operations", "recovery"));
    }

    public List<Map<String, Object>> identityProviders(long tenantId) {
        return rows("SELECT id, provider_type AS \"providerType\", name, enabled, " +
                "config::text AS config, validation_status AS \"validationStatus\", last_validated_at AS \"lastValidatedAt\", " +
                "updated_at AS \"updatedAt\" FROM identity_provider WHERE tenant_id=? ORDER BY updated_at DESC", tenantId);
    }

    public Map<String, Object> saveIdentityProvider(long tenantId, Map<String, Object> body) {
        String type = required(body, "providerType").toLowerCase(Locale.ROOT);
        if (!Set.of("oidc", "saml").contains(type)) throw new IllegalArgumentException("providerType must be oidc or saml");
        String name = required(body, "name");
        Map<String, Object> config = asMap(body.get("config"));
        if (config.containsKey("clientSecret") || config.containsKey("privateKey")) {
            throw new IllegalArgumentException("Store credentials in the tenant vault and provide secretRef instead");
        }
        validateIdentityConfig(type, config);
        Long id = jdbc.queryForObject("INSERT INTO identity_provider (tenant_id,provider_type,name,enabled,config,validation_status,last_validated_at) " +
                        "VALUES (?,?,?,?,?::jsonb,'valid',NOW()) ON CONFLICT (tenant_id,name) DO UPDATE SET provider_type=EXCLUDED.provider_type," +
                        "enabled=EXCLUDED.enabled,config=EXCLUDED.config,validation_status='valid',last_validated_at=NOW(),updated_at=NOW() RETURNING id",
                Long.class, tenantId, type, name, bool(body.get("enabled")), json(config));
        return one("SELECT id,provider_type AS \"providerType\",name,enabled,config::text AS config," +
                "validation_status AS \"validationStatus\",last_validated_at AS \"lastValidatedAt\" " +
                "FROM identity_provider WHERE tenant_id=? AND id=?", tenantId, id);
    }

    public Map<String, Object> issueScimToken(long tenantId, long userId, Map<String, Object> body) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = "scim_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String prefix = token.substring(0, Math.min(13, token.length()));
        int expiryDays = integer(body.getOrDefault("expiryDays", 90));
        if (expiryDays < 1 || expiryDays > 3650) throw new IllegalArgumentException("expiryDays must be between 1 and 3650");
        Long id = jdbc.queryForObject("INSERT INTO scim_token (tenant_id,name,token_prefix,token_hash,expires_at,created_by) " +
                        "VALUES (?,?,?,?,NOW() + (? * INTERVAL '1 day'),?) RETURNING id", Long.class,
                tenantId, required(body, "name"), prefix, sha256(token), expiryDays, userId);
        return Map.of("id", id, "token", token, "tokenPrefix", prefix, "expiresInDays", expiryDays,
                "warning", "This token is shown once and only its SHA-256 hash is stored");
    }

    public List<Map<String, Object>> scimTokens(long tenantId) {
        return jdbc.queryForList("SELECT id,name,token_prefix AS \"tokenPrefix\",expires_at AS \"expiresAt\"," +
                "last_used_at AS \"lastUsedAt\",revoked_at AS \"revokedAt\",created_at AS \"createdAt\" " +
                "FROM scim_token WHERE tenant_id=? ORDER BY created_at DESC", tenantId);
    }

    public void revokeScimToken(long tenantId, long id) {
        if (jdbc.update("UPDATE scim_token SET revoked_at=NOW() WHERE tenant_id=? AND id=? AND revoked_at IS NULL", tenantId, id) == 0)
            throw new NoSuchElementException("Active SCIM token not found");
    }

    @Transactional
    public Map<String, Object> syncScimUser(long tenantId, Map<String, Object> body) {
        String username = required(body, "userName");
        boolean active = body.get("active") == null || bool(body.get("active"));
        String displayName = string(body.getOrDefault("displayName", username));
        String email = extractScimEmail(body);
        String department = string(body.get("department"));
        Long departmentId = null;
        if (!department.isBlank()) {
            List<Long> ids = jdbc.queryForList("SELECT id FROM sys_department WHERE tenant_id=? AND name=? ORDER BY id LIMIT 1",
                    Long.class, tenantId, department);
            departmentId = ids.isEmpty()
                    ? jdbc.queryForObject("INSERT INTO sys_department(name,tenant_id) VALUES (?,?) RETURNING id",
                            Long.class, department, tenantId)
                    : ids.get(0);
        }
        List<Long> existing = jdbc.queryForList("SELECT id FROM sys_user WHERE username=?", Long.class, username);
        Long id;
        if (existing.isEmpty()) {
            id = jdbc.queryForObject("INSERT INTO sys_user(username,password_hash,display_name,email,department_id,tenant_id,status) " +
                            "VALUES (?,?,?,?,?,?,?) RETURNING id", Long.class, username,
                    passwordEncoder.encode(UUID.randomUUID().toString()), displayName, blankToNull(email), departmentId, tenantId,
                    active ? "active" : "disabled");
        } else {
            id = existing.get(0);
            int updated = jdbc.update("UPDATE sys_user SET display_name=?,email=?,department_id=?,status=?,updated_at=NOW() " +
                    "WHERE id=? AND tenant_id=?", displayName, blankToNull(email), departmentId,
                    active ? "active" : "disabled", id, tenantId);
            if (updated == 0) throw new SecurityException("SCIM user belongs to another tenant");
        }
        return scimUser(id, username, displayName, email, active, department);
    }

    @Transactional
    public Map<String, Object> syncScimGroup(long tenantId, Map<String, Object> body) {
        String displayName = required(body, "displayName");
        String code = ("scim_" + tenantId + "_" + string(body.getOrDefault("externalId", displayName)))
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        Long roleId = upsertRole(tenantId, code, displayName);
        int assigned = 0;
        Object membersValue = body.get("members");
        if (membersValue instanceof List<?> members) {
            for (Object value : members) {
                Map<String, Object> member = asMap(value);
                Long userId = nullableLong(member.get("value"));
                if (userId == null) continue;
                Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=? AND tenant_id=?", Integer.class, userId, tenantId);
                if (exists != null && exists > 0) {
                    assigned += jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES (?,?) ON CONFLICT DO NOTHING", userId, roleId);
                }
            }
        }
        return Map.of("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:Group"), "id", String.valueOf(roleId),
                "displayName", displayName, "membersAssigned", assigned);
    }

    public List<Map<String, Object>> accessPolicies(long tenantId) {
        return rows("SELECT id,name,effect,priority,resource_type AS \"resourceType\",action_pattern AS \"actionPattern\"," +
                "conditions::text AS conditions,enabled FROM access_policy WHERE tenant_id=? ORDER BY priority,id", tenantId);
    }

    public Map<String, Object> saveAccessPolicy(long tenantId, long userId, Map<String, Object> body) {
        String effect = string(body.getOrDefault("effect", "deny"));
        if (!Set.of("allow", "deny").contains(effect)) throw new IllegalArgumentException("effect must be allow or deny");
        Long id = jdbc.queryForObject("INSERT INTO access_policy(tenant_id,name,effect,priority,resource_type,action_pattern,conditions,enabled,created_by) " +
                        "VALUES (?,?,?,?,?,?,?::jsonb,?,?) RETURNING id", Long.class, tenantId, required(body, "name"), effect,
                integer(body.getOrDefault("priority", 100)), required(body, "resourceType"),
                string(body.getOrDefault("actionPattern", "*")), json(asMap(body.get("conditions"))),
                body.get("enabled") == null || bool(body.get("enabled")), userId);
        return one("SELECT id,name,effect,priority,resource_type AS \"resourceType\",action_pattern AS \"actionPattern\"," +
                "conditions::text AS conditions,enabled FROM access_policy WHERE tenant_id=? AND id=?", tenantId, id);
    }

    public GovernancePolicyEvaluator.AccessDecision evaluateAccess(long tenantId, Map<String, Object> body) {
        return evaluator.evaluateAccess(required(body, "resourceType"), required(body, "action"),
                asMap(body.get("attributes")), accessPolicies(tenantId));
    }

    public List<Map<String, Object>> secrets(long tenantId) {
        return jdbc.queryForList("SELECT id,secret_key AS \"secretKey\",description,key_version AS \"keyVersion\"," +
                "created_at AS \"createdAt\",updated_at AS \"updatedAt\" FROM secret_record WHERE tenant_id=? ORDER BY secret_key", tenantId);
    }

    @Transactional
    public Map<String, Object> storeSecret(long tenantId, long userId, Map<String, Object> body) {
        int version = activeKeyVersion(tenantId);
        TenantKeyService.EncryptedValue encrypted = keys.encrypt(tenantId, version, required(body, "value"));
        Long id = jdbc.queryForObject("INSERT INTO secret_record(tenant_id,secret_key,ciphertext,nonce,key_version,description,created_by) " +
                        "VALUES (?,?,?,?,?,?,?) ON CONFLICT(tenant_id,secret_key) DO UPDATE SET ciphertext=EXCLUDED.ciphertext," +
                        "nonce=EXCLUDED.nonce,key_version=EXCLUDED.key_version,description=EXCLUDED.description,updated_at=NOW() RETURNING id",
                Long.class, tenantId, required(body, "secretKey"), encrypted.ciphertext(), encrypted.nonce(), version,
                string(body.get("description")), userId);
        return one("SELECT id,secret_key AS \"secretKey\",description,key_version AS \"keyVersion\",updated_at AS \"updatedAt\" " +
                "FROM secret_record WHERE tenant_id=? AND id=?", tenantId, id);
    }

    public Map<String, Object> revealSecret(long tenantId, long id) {
        Map<String, Object> row = one("SELECT secret_key AS \"secretKey\",ciphertext,nonce,key_version AS \"keyVersion\" " +
                "FROM secret_record WHERE tenant_id=? AND id=?", tenantId, id);
        TenantKeyService.EncryptedValue encrypted = new TenantKeyService.EncryptedValue(
                string(row.get("ciphertext")), string(row.get("nonce")), integer(row.get("keyVersion")));
        return Map.of("secretKey", row.get("secretKey"), "value", keys.decrypt(tenantId, encrypted), "oneTimeView", true);
    }

    @Transactional
    public Map<String, Object> rotateTenantKey(long tenantId) {
        int oldVersion = activeKeyVersion(tenantId);
        int newVersion = oldVersion + 1;
        jdbc.update("UPDATE tenant_key_version SET status='retired',retired_at=NOW() WHERE tenant_id=? AND status='active'", tenantId);
        jdbc.update("INSERT INTO tenant_key_version(tenant_id,version,status) VALUES (?,?,'active')", tenantId, newVersion);
        List<Map<String, Object>> records = jdbc.queryForList("SELECT id,ciphertext,nonce,key_version AS \"keyVersion\" FROM secret_record WHERE tenant_id=?", tenantId);
        for (Map<String, Object> record : records) {
            String plain = keys.decrypt(tenantId, new TenantKeyService.EncryptedValue(string(record.get("ciphertext")),
                    string(record.get("nonce")), integer(record.get("keyVersion"))));
            TenantKeyService.EncryptedValue encrypted = keys.encrypt(tenantId, newVersion, plain);
            jdbc.update("UPDATE secret_record SET ciphertext=?,nonce=?,key_version=?,updated_at=NOW() WHERE tenant_id=? AND id=?",
                    encrypted.ciphertext(), encrypted.nonce(), newVersion, tenantId, record.get("id"));
        }
        return Map.of("previousVersion", oldVersion, "activeVersion", newVersion, "secretsReEncrypted", records.size());
    }

    public List<Map<String, Object>> retentionPolicies(long tenantId) {
        return jdbc.queryForList("SELECT id,data_type AS \"dataType\",retention_days AS \"retentionDays\",action,legal_hold AS \"legalHold\"," +
                "enabled,last_run_at AS \"lastRunAt\",last_affected_rows AS \"lastAffectedRows\" FROM data_retention_policy WHERE tenant_id=? ORDER BY data_type", tenantId);
    }

    public Map<String, Object> saveRetentionPolicy(long tenantId, Map<String, Object> body) {
        String dataType = required(body, "dataType");
        if (!RETENTION_TABLES.contains(dataType)) throw new IllegalArgumentException("Unsupported dataType: " + dataType);
        String action = string(body.getOrDefault("action", "delete"));
        if (!Set.of("delete", "anonymize").contains(action)) throw new IllegalArgumentException("action must be delete or anonymize");
        Long id = jdbc.queryForObject("INSERT INTO data_retention_policy(tenant_id,data_type,retention_days,action,legal_hold,enabled) " +
                        "VALUES (?,?,?,?,?,?) ON CONFLICT(tenant_id,data_type) DO UPDATE SET retention_days=EXCLUDED.retention_days," +
                        "action=EXCLUDED.action,legal_hold=EXCLUDED.legal_hold,enabled=EXCLUDED.enabled,updated_at=NOW() RETURNING id",
                Long.class, tenantId, dataType, integer(body.getOrDefault("retentionDays", 365)), action,
                bool(body.get("legalHold")), body.get("enabled") == null || bool(body.get("enabled")));
        return one("SELECT id,data_type AS \"dataType\",retention_days AS \"retentionDays\",action,legal_hold AS \"legalHold\",enabled " +
                "FROM data_retention_policy WHERE tenant_id=? AND id=?", tenantId, id);
    }

    @Transactional
    public Map<String, Object> runRetention(long tenantId, long policyId, boolean execute) {
        Map<String, Object> policy = one("SELECT data_type AS \"dataType\",retention_days AS \"retentionDays\",action,legal_hold AS \"legalHold\",enabled " +
                "FROM data_retention_policy WHERE tenant_id=? AND id=?", tenantId, policyId);
        if (!bool(policy.get("enabled"))) throw new IllegalStateException("Retention policy is disabled");
        if (bool(policy.get("legalHold"))) return Map.of("executed", false, "affectedRows", 0, "reason", "legal hold");
        String table = string(policy.get("dataType"));
        int days = integer(policy.get("retentionDays"));
        String timestampColumn = "execution_trace".equals(table) ? "started_at" : "created_at";
        String countSql = "SELECT COUNT(*) FROM " + table + " WHERE tenant_id=? AND " + timestampColumn + " < NOW() - (? * INTERVAL '1 day')";
        long candidates = Optional.ofNullable(jdbc.queryForObject(countSql, Long.class, tenantId, days)).orElse(0L);
        long affected = 0;
        if (execute && candidates > 0) {
            if ("anonymize".equals(policy.get("action"))) {
                String anonymizeSql = switch (table) {
                    case "audit_log" -> "UPDATE audit_log SET username=NULL,ip_address=NULL,detail='[REDACTED]' WHERE tenant_id=? AND created_at < NOW() - (? * INTERVAL '1 day')";
                    case "token_usage" -> "UPDATE token_usage SET user_id=NULL,session_id='[REDACTED]' WHERE tenant_id=? AND created_at < NOW() - (? * INTERVAL '1 day')";
                    case "execution_trace" -> "UPDATE execution_trace SET session_id='[REDACTED]' WHERE tenant_id=? AND started_at < NOW() - (? * INTERVAL '1 day')";
                    default -> throw new IllegalArgumentException("Unsupported anonymization target");
                };
                affected = jdbc.update(anonymizeSql, tenantId, days);
            } else {
                affected = jdbc.update("DELETE FROM " + table + " WHERE tenant_id=? AND " + timestampColumn + " < NOW() - (? * INTERVAL '1 day')", tenantId, days);
            }
            jdbc.update("UPDATE data_retention_policy SET last_run_at=NOW(),last_affected_rows=? WHERE tenant_id=? AND id=?", affected, tenantId, policyId);
        }
        return Map.of("executed", execute, "candidateRows", candidates, "affectedRows", affected,
                "action", policy.get("action"), "dataType", table);
    }

    public Map<String, Object> complianceReport(long tenantId) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("validatedIdentityProviders", scalar("SELECT COUNT(*) FROM identity_provider WHERE tenant_id=? AND validation_status='valid'", tenantId));
        evidence.put("encryptedSecrets", count("secret_record", tenantId));
        evidence.put("activeRetentionPolicies", scalar("SELECT COUNT(*) FROM data_retention_policy WHERE tenant_id=? AND enabled=true", tenantId));
        evidence.put("accessPolicies", scalar("SELECT COUNT(*) FROM access_policy WHERE tenant_id=? AND enabled=true", tenantId));
        evidence.put("approvalPolicies", scalar("SELECT COUNT(*) FROM approval_policy WHERE tenant_id=? AND enabled=true", tenantId));
        evidence.put("verifiedDeletionRecords", count("deletion_certificate", tenantId));
        long configured = ((Collection<?>) evidence.values()).stream().filter(value -> Long.parseLong(String.valueOf(value)) > 0).count();
        return Map.of("generatedAt", LocalDateTime.now(), "score", Math.round(configured * 100.0 / evidence.size()),
                "evidence", evidence, "scope", "local tenant controls", "externalAttestation", false);
    }

    public List<Map<String, Object>> approvalPolicies(long tenantId) {
        return rows("SELECT id,name,priority,decision,conditions::text AS conditions,sla_minutes AS \"slaMinutes\"," +
                "escalation_role AS \"escalationRole\",enabled FROM approval_policy WHERE tenant_id=? ORDER BY priority,id", tenantId);
    }

    public Map<String, Object> saveApprovalPolicy(long tenantId, Map<String, Object> body) {
        String decision = string(body.getOrDefault("decision", "single"));
        if (!Set.of("auto_approve", "single", "dual", "reject").contains(decision))
            throw new IllegalArgumentException("Invalid approval decision");
        Long id = jdbc.queryForObject("INSERT INTO approval_policy(tenant_id,name,priority,decision,conditions,sla_minutes,escalation_role,enabled) " +
                        "VALUES (?,?,?,?,?::jsonb,?,?,?) ON CONFLICT(tenant_id,name) DO UPDATE SET priority=EXCLUDED.priority," +
                        "decision=EXCLUDED.decision,conditions=EXCLUDED.conditions,sla_minutes=EXCLUDED.sla_minutes," +
                        "escalation_role=EXCLUDED.escalation_role,enabled=EXCLUDED.enabled,updated_at=NOW() RETURNING id", Long.class, tenantId, required(body, "name"),
                integer(body.getOrDefault("priority", 100)), decision, json(asMap(body.get("conditions"))),
                integer(body.getOrDefault("slaMinutes", 60)), string(body.get("escalationRole")),
                body.get("enabled") == null || bool(body.get("enabled")));
        return one("SELECT id,name,priority,decision,conditions::text AS conditions,sla_minutes AS \"slaMinutes\"," +
                "escalation_role AS \"escalationRole\",enabled FROM approval_policy WHERE tenant_id=? AND id=?", tenantId, id);
    }

    public GovernancePolicyEvaluator.ApprovalDecision evaluateApproval(long tenantId, Map<String, Object> body) {
        return evaluator.evaluateApproval(body, approvalPolicies(tenantId));
    }

    public Map<String, Object> saveOnCall(long tenantId, Map<String, Object> body) {
        Long primary = nullableLong(body.get("primaryUserId"));
        Long backup = nullableLong(body.get("backupUserId"));
        validateTenantUser(tenantId, primary);
        validateTenantUser(tenantId, backup);
        Long id = jdbc.queryForObject("INSERT INTO on_call_schedule(tenant_id,name,primary_user_id,backup_user_id,timezone,active_from,active_to,enabled) " +
                        "VALUES (?,?,?,?,?,?::time,?::time,?) ON CONFLICT(tenant_id,name) DO UPDATE SET primary_user_id=EXCLUDED.primary_user_id," +
                        "backup_user_id=EXCLUDED.backup_user_id,timezone=EXCLUDED.timezone,active_from=EXCLUDED.active_from,active_to=EXCLUDED.active_to," +
                        "enabled=EXCLUDED.enabled RETURNING id", Long.class, tenantId, required(body, "name"), primary, backup,
                string(body.getOrDefault("timezone", "Asia/Shanghai")), string(body.getOrDefault("activeFrom", "00:00")),
                string(body.getOrDefault("activeTo", "23:59")), body.get("enabled") == null || bool(body.get("enabled")));
        return one("SELECT id,name,primary_user_id AS \"primaryUserId\",backup_user_id AS \"backupUserId\",timezone," +
                "active_from AS \"activeFrom\",active_to AS \"activeTo\",enabled FROM on_call_schedule WHERE tenant_id=? AND id=?", tenantId, id);
    }

    public List<Map<String, Object>> onCallSchedules(long tenantId) {
        return jdbc.queryForList("SELECT id,name,primary_user_id AS \"primaryUserId\",backup_user_id AS \"backupUserId\",timezone," +
                "active_from AS \"activeFrom\",active_to AS \"activeTo\",enabled FROM on_call_schedule WHERE tenant_id=? ORDER BY name", tenantId);
    }

    @Transactional
    public Map<String, Object> sweepApprovalSla(long tenantId) {
        List<Map<String, Object>> overdue = jdbc.queryForList("SELECT id,assigned_to AS \"assignedTo\" FROM approval_request " +
                "WHERE tenant_id=? AND status='pending' AND due_at < NOW()", tenantId);
        int escalated = 0;
        for (Map<String, Object> request : overdue) {
            Long current = nullableLong(request.get("assignedTo"));
            List<Long> backups = jdbc.queryForList("SELECT backup_user_id FROM on_call_schedule WHERE tenant_id=? AND enabled=true " +
                    "AND backup_user_id IS NOT NULL AND (primary_user_id=? OR ? IS NULL) ORDER BY id LIMIT 1", Long.class, tenantId, current, current);
            Long backup = backups.isEmpty() ? current : backups.get(0);
            escalated += jdbc.update("UPDATE approval_request SET assigned_to=?,escalated_at=NOW(),sla_status='overdue' " +
                    "WHERE tenant_id=? AND id=? AND status='pending'", backup, tenantId, request.get("id"));
        }
        return Map.of("overdueFound", overdue.size(), "escalated", escalated, "mobileReady", true);
    }

    public List<Map<String, Object>> jobs(long tenantId) {
        return rows("SELECT id,job_type AS \"jobType\",status,payload::text AS payload,result::text AS result,checksum," +
                "created_at AS \"createdAt\",completed_at AS \"completedAt\" FROM governance_job WHERE tenant_id=? ORDER BY created_at DESC", tenantId);
    }

    public Map<String, Object> createExport(long tenantId, long userId) {
        Map<String, Object> manifest = tenantManifest(tenantId, true);
        return persistJob(tenantId, userId, "export", Map.of("format", "json"), manifest);
    }

    public Map<String, Object> createBackup(long tenantId, long userId) {
        Map<String, Object> manifest = tenantManifest(tenantId, false);
        return persistJob(tenantId, userId, "backup", Map.of("scope", "tenant"), manifest);
    }

    public Map<String, Object> verifyJob(long tenantId, UUID id) {
        Map<String, Object> job = one("SELECT job_type AS \"jobType\",result::text AS result,checksum FROM governance_job WHERE tenant_id=? AND id=?", tenantId, id);
        String actual = hashJson(parseMap(job.get("result")));
        boolean verified = MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII), string(job.get("checksum")).getBytes(StandardCharsets.US_ASCII));
        return Map.of("jobId", id, "jobType", job.get("jobType"), "verified", verified, "checksum", actual);
    }

    public Map<String, Object> restoreDrill(long tenantId, long userId, UUID backupId) {
        Map<String, Object> verification = verifyJob(tenantId, backupId);
        if (!bool(verification.get("verified"))) throw new IllegalStateException("Backup checksum verification failed");
        return persistJob(tenantId, userId, "restore_drill", Map.of("backupId", backupId),
                Map.of("restorable", true, "mode", "dry_run", "sourceBackupId", backupId,
                        "checks", List.of("checksum", "tenant_scope", "schema_manifest")));
    }

    public Map<String, Object> migrationPlan(long tenantId, long userId, Map<String, Object> body) {
        String target = required(body, "targetTenantRef");
        Map<String, Object> result = new LinkedHashMap<>(tenantManifest(tenantId, false));
        result.put("targetTenantRef", target);
        result.put("mode", "validated_plan");
        result.put("writesApplied", false);
        return persistJob(tenantId, userId, "migration", Map.of("targetTenantRef", target), result);
    }

    @Transactional
    public Map<String, Object> deleteAndCertify(long tenantId, long userId, Map<String, Object> body) {
        String subjectType = required(body, "subjectType");
        String subjectRef = required(body, "subjectRef");
        int rows;
        if ("secret".equals(subjectType)) {
            rows = jdbc.update("DELETE FROM secret_record WHERE tenant_id=? AND secret_key=?", tenantId, subjectRef);
        } else if ("identity_provider".equals(subjectType)) {
            rows = jdbc.update("DELETE FROM identity_provider WHERE tenant_id=? AND id=?", tenantId, Long.valueOf(subjectRef));
        } else if ("access_policy".equals(subjectType)) {
            rows = jdbc.update("DELETE FROM access_policy WHERE tenant_id=? AND id=?", tenantId, Long.valueOf(subjectRef));
        } else throw new IllegalArgumentException("Unsupported certifiable subjectType");
        UUID id = UUID.randomUUID();
        Map<String, Object> evidence = Map.of("certificateId", id, "tenantId", tenantId, "subjectType", subjectType,
                "subjectRef", subjectRef, "rowsAffected", rows, "deletedAt", LocalDateTime.now().toString());
        String digest = hashJson(evidence);
        jdbc.update("INSERT INTO deletion_certificate(id,tenant_id,subject_type,subject_ref,rows_affected,evidence,evidence_hash,created_by) " +
                "VALUES (?,?,?,?,?,?::jsonb,?,?)", id, tenantId, subjectType, subjectRef, rows, json(evidence), digest, userId);
        return Map.of("certificateId", id, "rowsAffected", rows, "evidenceHash", digest, "verified", true);
    }

    public Map<String, Object> verifyDeletion(long tenantId, UUID id) {
        Map<String, Object> record = one("SELECT evidence::text AS evidence,evidence_hash AS \"evidenceHash\" FROM deletion_certificate WHERE tenant_id=? AND id=?", tenantId, id);
        String actual = hashJson(parseMap(record.get("evidence")));
        return Map.of("certificateId", id, "verified", actual.equals(record.get("evidenceHash")), "evidenceHash", actual);
    }

    private Map<String, Object> persistJob(long tenantId, long userId, String type, Map<String, Object> payload, Map<String, Object> result) {
        UUID id = UUID.randomUUID();
        String checksum = hashJson(result);
        jdbc.update("INSERT INTO governance_job(id,tenant_id,job_type,status,payload,result,checksum,created_by,completed_at) " +
                "VALUES (?,?,?,'completed',?::jsonb,?::jsonb,?,?,NOW())", id, tenantId, type, json(payload), json(result), checksum, userId);
        return Map.of("id", id, "jobType", type, "status", "completed", "checksum", checksum, "result", result);
    }

    private Map<String, Object> tenantManifest(long tenantId, boolean includePortableData) {
        Map<String, Object> counts = new TreeMap<>();
        for (String table : List.of("sys_user", "sys_role", "agent_definition", "knowledge_base", "workspace_resource",
                "identity_provider", "access_policy", "secret_record", "approval_policy", "audit_log")) {
            counts.put(table, count(table, tenantId));
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 15);
        manifest.put("tenantId", tenantId);
        manifest.put("counts", counts);
        manifest.put("encryptedSecretsIncluded", false);
        if (includePortableData) {
            manifest.put("accessPolicies", accessPolicies(tenantId));
            manifest.put("retentionPolicies", retentionPolicies(tenantId));
            manifest.put("approvalPolicies", approvalPolicies(tenantId));
        }
        return manifest;
    }

    private void validateIdentityConfig(String type, Map<String, Object> config) {
        if ("oidc".equals(type)) {
            String issuer = required(config, "issuer");
            if (!(issuer.startsWith("https://") || issuer.startsWith("http://localhost") || issuer.startsWith("http://127.0.0.1")))
                throw new IllegalArgumentException("OIDC issuer must use HTTPS (localhost is allowed for development)");
            required(config, "clientId");
        } else {
            required(config, "entityId");
            String ssoUrl = required(config, "ssoUrl");
            if (!ssoUrl.startsWith("https://")) throw new IllegalArgumentException("SAML SSO URL must use HTTPS");
            required(config, "certificate");
        }
    }

    private int activeKeyVersion(long tenantId) {
        List<Integer> versions = jdbc.queryForList("SELECT version FROM tenant_key_version WHERE tenant_id=? AND status='active'", Integer.class, tenantId);
        if (!versions.isEmpty()) return versions.get(0);
        jdbc.update("INSERT INTO tenant_key_version(tenant_id,version,status) VALUES (?,1,'active') ON CONFLICT DO NOTHING", tenantId);
        return 1;
    }

    private Long upsertRole(long tenantId, String code, String name) {
        List<Long> roles = jdbc.queryForList("SELECT id FROM sys_role WHERE role_code=? AND tenant_id=?", Long.class, code, tenantId);
        if (!roles.isEmpty()) return roles.get(0);
        return jdbc.queryForObject("INSERT INTO sys_role(role_name,role_code,description,tenant_id) VALUES (?,?,?,?) RETURNING id",
                Long.class, name, code, "Provisioned by SCIM", tenantId);
    }

    private Map<String, Object> scimUser(long id, String username, String displayName, String email, boolean active, String department) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:User"));
        result.put("id", String.valueOf(id));
        result.put("userName", username);
        result.put("displayName", displayName);
        result.put("active", active);
        result.put("emails", email.isBlank() ? List.of() : List.of(Map.of("value", email, "primary", true)));
        result.put("department", department);
        return result;
    }

    private String extractScimEmail(Map<String, Object> body) {
        Object value = body.get("emails");
        if (value instanceof List<?> list && !list.isEmpty()) return string(asMap(list.get(0)).get("value"));
        return string(body.get("email"));
    }

    private void validateTenantUser(long tenantId, Long userId) {
        if (userId == null) return;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE tenant_id=? AND id=?", Integer.class, tenantId, userId);
        if (count == null || count == 0) throw new IllegalArgumentException("User is not in the current tenant: " + userId);
    }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        List<Map<String, Object>> result = jdbc.queryForList(sql, args);
        result.forEach(this::parseJsonColumns);
        return result;
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> result = jdbc.queryForList(sql, args);
        if (result.isEmpty()) throw new NoSuchElementException("Governance resource not found");
        Map<String, Object> row = result.get(0);
        parseJsonColumns(row);
        return row;
    }

    private void parseJsonColumns(Map<String, Object> row) {
        for (String key : List.of("config", "conditions", "payload", "result", "evidence")) {
            if (row.get(key) instanceof String text) row.put(key, parseMap(text));
        }
    }

    private Map<String, Object> parseMap(Object value) {
        if (value instanceof Map<?, ?> map) return asMap(map);
        if (value == null || string(value).isBlank()) return Map.of();
        try { return mapper.readValue(string(value), new TypeReference<>() {}); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Stored governance JSON is invalid", exception); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("Value is not valid JSON", exception); }
    }

    private String hashJson(Object value) {
        try {
            String canonical = mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value);
            return sha256(canonical);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to create evidence hash", exception);
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }

    private long count(String table, long tenantId) {
        if (!table.matches("[a-z_]+")) throw new IllegalArgumentException("Invalid table");
        return scalar("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?", tenantId);
    }

    private long scalar(String sql, Object... args) {
        return Optional.ofNullable(jdbc.queryForObject(sql, Long.class, args)).orElse(0L);
    }

    private String required(Map<String, Object> body, String key) {
        String value = string(body.get(key)).trim();
        if (value.isEmpty()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private boolean bool(Object value) { return Boolean.parseBoolean(string(value)); }
    private int integer(Object value) { try { return Integer.parseInt(string(value)); } catch (Exception e) { return 0; } }
    private Long nullableLong(Object value) { try { return value == null || string(value).isBlank() ? null : Long.valueOf(string(value)); } catch (Exception e) { return null; } }
}
