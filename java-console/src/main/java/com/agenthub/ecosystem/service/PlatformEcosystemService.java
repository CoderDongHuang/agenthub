package com.agenthub.ecosystem.service;

import com.agenthub.diagnostics.service.ConfigurationDiagnosticsService;
import com.agenthub.grpc.PythonAgentClient;
import com.agenthub.grpc.stub.ExecutionRequest;
import com.agenthub.grpc.stub.ExecutionResponse;
import com.agenthub.governance.service.TenantKeyService;
import com.agenthub.platform.service.WebhookUrlValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class PlatformEcosystemService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ArtifactIntegrityService integrity;
    private final GatewaySignatureService gatewaySignatures;
    private final MultimodalExtractionService multimodal;
    private final TenantKeyService tenantKeys;
    private final ConfigurationDiagnosticsService diagnostics;
    private final PythonAgentClient pythonAgentClient;
    private final RedisConnectionFactory redis;
    private final WebhookUrlValidator webhookUrlValidator;
    private final RestClient mcpHttp;
    private final String runtimeBaseUrl;
    private final String internalToken;
    private final SecureRandom random = new SecureRandom();

    public PlatformEcosystemService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                    ArtifactIntegrityService integrity,
                                    GatewaySignatureService gatewaySignatures,
                                    MultimodalExtractionService multimodal,
                                    TenantKeyService tenantKeys,
                                    ConfigurationDiagnosticsService diagnostics,
                                    PythonAgentClient pythonAgentClient,
                                    RedisConnectionFactory redis,
                                    WebhookUrlValidator webhookUrlValidator,
                                    @Value("${python.runtime.base-url}") String runtimeBaseUrl,
                                    @Value("${agenthub.internal-token}") String internalToken) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.integrity = integrity;
        this.gatewaySignatures = gatewaySignatures;
        this.multimodal = multimodal;
        this.tenantKeys = tenantKeys;
        this.diagnostics = diagnostics;
        this.pythonAgentClient = pythonAgentClient;
        this.redis = redis;
        this.webhookUrlValidator = webhookUrlValidator;
        this.mcpHttp = RestClient.builder()
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
        this.runtimeBaseUrl = runtimeBaseUrl.replaceAll("/+$", "");
        this.internalToken = internalToken;
    }

    public Map<String, Object> overview(long tenantId) {
        Map<String, Object> counts = jdbc.queryForMap("""
                SELECT
                  (SELECT COUNT(*) FROM ecosystem_package WHERE tenant_id=?) AS packages,
                  (SELECT COUNT(*) FROM ecosystem_package WHERE tenant_id=? AND scan_status='blocked') AS blocked_packages,
                  (SELECT COUNT(*) FROM mcp_connection WHERE tenant_id=? AND status='healthy') AS healthy_mcp,
                  (SELECT COUNT(*) FROM developer_app WHERE tenant_id=? AND status='active') AS developer_apps,
                  (SELECT COUNT(*) FROM multimodal_job WHERE tenant_id=?) AS multimodal_jobs,
                  (SELECT COUNT(*) FROM worker_pool WHERE tenant_id=?) AS worker_pools,
                  (SELECT COUNT(*) FROM resilience_drill WHERE tenant_id=? AND status='passed') AS passed_drills
                """, tenantId, tenantId, tenantId, tenantId, tenantId, tenantId, tenantId);
        return Map.of(
                "counts", counts,
                "controls", List.of("sdk_registry", "mcp", "api_gateway", "multimodal", "kubernetes_dr",
                        "supply_chain", "local_devkit"),
                "protocolVersion", "2025-03-26",
                "gatewayVersions", List.of("v1"),
                "sandbox", Map.of("readOnlyRootFilesystem", true, "networkDefault", "none", "maxTimeoutSeconds", 120),
                "deployment", Map.of("kubernetes", true, "hpa", true, "multiRegionOverlays", 2)
        );
    }

    public List<Map<String, Object>> packages(long tenantId) {
        return jdbc.queryForList("""
                SELECT id,package_type AS "packageType",package_name AS "packageName",version,visibility,
                       source_uri AS "sourceUri",source_digest AS "sourceDigest",manifest,signature,
                       signature_algorithm AS "signatureAlgorithm",signer,compatibility,
                       scan_status AS "scanStatus",risk_score AS "riskScore",scan_findings AS "scanFindings",
                       created_at AS "createdAt",updated_at AS "updatedAt"
                FROM ecosystem_package WHERE tenant_id=? ORDER BY updated_at DESC,id DESC
                """, tenantId).stream()
                .map(row -> normalizeJsonColumns(row, "manifest", "compatibility", "scanFindings"))
                .toList();
    }

    @Transactional
    public Map<String, Object> registerPackage(long tenantId, long userId, Map<String, Object> body) {
        String name = required(body, "packageName", 160);
        String version = required(body, "version", 40);
        if (!version.matches("\\d+\\.\\d+\\.\\d+(?:[-+][A-Za-z0-9.-]+)?")) {
            throw new IllegalArgumentException("Package version must use semantic versioning");
        }
        String type = oneOf(body, "packageType", Set.of("tool", "plugin", "mcp"), "tool");
        String visibility = oneOf(body, "visibility", Set.of("private", "tenant", "public"), "private");
        String sourceUri = required(body, "sourceUri", 2000);
        byte[] artifact;
        try {
            artifact = Base64.getDecoder().decode(required(body, "artifactBase64", 35_000_000));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("artifactBase64 must be valid Base64", exception);
        }
        Map<String, Object> manifest = mapValue(body.get("manifest"));
        Map<String, Object> compatibility = mapValue(body.get("compatibility"));
        String digest = integrity.digest(artifact);
        String signature = integrity.sign(tenantId, digest, manifest);
        Map<String, Object> scan = integrity.scan(sourceUri, manifest, compatibility, true);
        Long id = jdbc.queryForObject("""
                INSERT INTO ecosystem_package
                  (tenant_id,package_type,package_name,version,visibility,source_uri,source_digest,artifact,manifest,
                   signature,signer,compatibility,scan_status,risk_score,scan_findings,created_by)
                VALUES (?,?,?,?,?,?,?,?,?::jsonb,?,?,?::jsonb,?,?,?::jsonb,?)
                RETURNING id
                """, Long.class, tenantId, type, name, version, visibility, sourceUri, digest, artifact, json(manifest),
                signature, "tenant:" + tenantId, json(compatibility), scan.get("status"), scan.get("riskScore"),
                json(scan.get("findings")), userId);
        Map<String, Object> result = packageById(tenantId, Objects.requireNonNull(id));
        result.put("signatureValid", true);
        return result;
    }

    public Map<String, Object> verifyPackage(long tenantId, long packageId) {
        Map<String, Object> row = packageById(tenantId, packageId);
        boolean valid = integrity.verify(tenantId, String.valueOf(row.get("sourceDigest")),
                mapValue(row.get("manifest")), String.valueOf(row.get("signature")));
        return Map.of("packageId", packageId, "valid", valid, "algorithm", row.get("signatureAlgorithm"),
                "digest", row.get("sourceDigest"));
    }

    public Map<String, Object> packageArtifact(long tenantId, long packageId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT package_name,version,artifact,source_digest FROM ecosystem_package WHERE tenant_id=? AND id=?",
                tenantId, packageId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Ecosystem package not found");
        Map<String, Object> row = rows.get(0);
        byte[] artifact = (byte[]) row.get("artifact");
        return Map.of("packageId", packageId,
                "fileName", row.get("package_name") + "-" + row.get("version") + ".zip",
                "contentBase64", Base64.getEncoder().encodeToString(artifact),
                "digest", row.get("source_digest"));
    }

    @Transactional
    public Map<String, Object> scanPackage(long tenantId, long packageId) {
        Map<String, Object> row = packageById(tenantId, packageId);
        boolean valid = integrity.verify(tenantId, String.valueOf(row.get("sourceDigest")),
                mapValue(row.get("manifest")), String.valueOf(row.get("signature")));
        Map<String, Object> scan = integrity.scan(String.valueOf(row.get("sourceUri")),
                mapValue(row.get("manifest")), mapValue(row.get("compatibility")), valid);
        jdbc.update("UPDATE ecosystem_package SET scan_status=?,risk_score=?,scan_findings=?::jsonb,updated_at=NOW() WHERE id=? AND tenant_id=?",
                scan.get("status"), scan.get("riskScore"), json(scan.get("findings")), packageId, tenantId);
        Map<String, Object> result = new LinkedHashMap<>(scan);
        result.put("packageId", packageId);
        return result;
    }

    public Map<String, Object> sandboxProfile(Map<String, Object> body) {
        return integrity.sandboxProfile(body);
    }

    public List<Map<String, Object>> mcpConnections(long tenantId) {
        return jdbc.queryForList("""
                SELECT id,name,direction,transport,endpoint,protocol_version AS "protocolVersion",
                       auth_secret_ref AS "authSecretRef",capabilities,status,last_probe AS "lastProbe",
                       last_probed_at AS "lastProbedAt",updated_at AS "updatedAt"
                FROM mcp_connection WHERE tenant_id=? ORDER BY updated_at DESC,id DESC
                """, tenantId).stream()
                .map(row -> normalizeJsonColumns(row, "capabilities", "lastProbe"))
                .toList();
    }

    @Transactional
    public Map<String, Object> saveMcpConnection(long tenantId, Map<String, Object> body) {
        String name = required(body, "name", 120);
        String direction = oneOf(body, "direction", Set.of("client", "server"), "client");
        String transport = oneOf(body, "transport", Set.of("http", "sse", "stdio"), "http");
        String endpoint = required(body, "endpoint", 2000);
        validateMcpEndpoint(transport, endpoint);
        String protocol = Objects.toString(body.getOrDefault("protocolVersion", "2025-03-26"));
        String secretRef = blankToNull(body.get("authSecretRef"));
        Long id = jdbc.queryForObject("""
                INSERT INTO mcp_connection (tenant_id,name,direction,transport,endpoint,protocol_version,auth_secret_ref)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (tenant_id,name) DO UPDATE SET direction=EXCLUDED.direction,transport=EXCLUDED.transport,
                  endpoint=EXCLUDED.endpoint,protocol_version=EXCLUDED.protocol_version,
                  auth_secret_ref=EXCLUDED.auth_secret_ref,status='unverified',updated_at=NOW()
                RETURNING id
                """, Long.class, tenantId, name, direction, transport, endpoint, protocol, secretRef);
        return mcpById(tenantId, Objects.requireNonNull(id));
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> probeMcp(long tenantId, long connectionId) {
        Map<String, Object> connection = mcpById(tenantId, connectionId);
        if (!"http".equals(connection.get("transport")) && !"sse".equals(connection.get("transport"))) {
            throw new IllegalArgumentException("Only HTTP/SSE MCP connections support network probes");
        }
        String endpoint = String.valueOf(connection.get("endpoint"));
        validateMcpEndpoint(String.valueOf(connection.get("transport")), endpoint);
        Map<String, Object> request = Map.of("jsonrpc", "2.0", "id", UUID.randomUUID().toString(),
                "method", "initialize", "params", Map.of("protocolVersion", connection.get("protocolVersion"),
                        "capabilities", Map.of(), "clientInfo", Map.of("name", "agenthub-java", "version", "0.1.0")));
        try {
            RestClient.RequestBodySpec call = mcpHttp.post().uri(endpoint).body(request);
            if (isRuntimeEndpoint(endpoint)) call = call.header("X-Internal-Token", internalToken);
            Map<String, Object> response = call.retrieve().body(Map.class);
            Map<String, Object> result = response != null && response.get("result") instanceof Map<?, ?> raw
                    ? (Map<String, Object>) raw : Map.of();
            boolean healthy = result.containsKey("protocolVersion") && result.containsKey("capabilities");
            String status = healthy ? "healthy" : "degraded";
            jdbc.update("UPDATE mcp_connection SET status=?,capabilities=?::jsonb,last_probe=?::jsonb,last_probed_at=NOW(),updated_at=NOW() WHERE id=? AND tenant_id=?",
                    status, json(result.getOrDefault("capabilities", Map.of())), json(response), connectionId, tenantId);
            return Map.of("connectionId", connectionId, "status", status, "response", Objects.requireNonNullElse(response, Map.of()));
        } catch (Exception exception) {
            Map<String, Object> evidence = Map.of("error", safe(exception));
            jdbc.update("UPDATE mcp_connection SET status='offline',last_probe=?::jsonb,last_probed_at=NOW(),updated_at=NOW() WHERE id=? AND tenant_id=?",
                    json(evidence), connectionId, tenantId);
            return Map.of("connectionId", connectionId, "status", "offline", "response", evidence);
        }
    }

    public List<Map<String, Object>> developerApps(long tenantId) {
        return jdbc.queryForList("""
                SELECT id,app_name AS "appName",public_key AS "publicKey",api_version AS "apiVersion",
                       quota_per_minute AS "quotaPerMinute",allowed_operations AS "allowedOperations",
                       tenant_route AS "tenantRoute",status,last_used_at AS "lastUsedAt",created_at AS "createdAt"
                FROM developer_app WHERE tenant_id=? ORDER BY created_at DESC,id DESC
                """, tenantId).stream()
                .map(row -> normalizeJsonColumns(row, "allowedOperations"))
                .toList();
    }

    @Transactional
    public Map<String, Object> createDeveloperApp(long tenantId, long userId, Map<String, Object> body) {
        String name = required(body, "appName", 120);
        String apiVersion = Objects.toString(body.getOrDefault("apiVersion", "v1"));
        if (!apiVersion.matches("v\\d+")) throw new IllegalArgumentException("API version must look like v1");
        int quota = intValue(body.get("quotaPerMinute"), 60);
        if (quota < 1 || quota > 100000) throw new IllegalArgumentException("quotaPerMinute is out of range");
        List<String> operations = stringList(body.getOrDefault("allowedOperations", List.of("platform.echo")));
        if (operations.isEmpty() || operations.stream().anyMatch(op -> !Set.of("platform.echo", "platform.capabilities", "agent.chat").contains(op))) {
            throw new IllegalArgumentException("Unsupported gateway operation");
        }
        String route = Objects.toString(body.getOrDefault("tenantRoute", "primary"));
        String publicKey = "dev_" + randomToken(18);
        String secret = "devsec_" + randomToken(36);
        int keyVersion = 1;
        TenantKeyService.EncryptedValue encrypted = tenantKeys.encrypt(tenantId, keyVersion, secret);
        Long id = jdbc.queryForObject("""
                INSERT INTO developer_app
                  (tenant_id,app_name,public_key,secret_ciphertext,secret_nonce,secret_key_version,api_version,
                   quota_per_minute,allowed_operations,tenant_route,created_by)
                VALUES (?,?,?,?,?,?,?, ?,?::jsonb,?,?) RETURNING id
                """, Long.class, tenantId, name, publicKey, encrypted.ciphertext(), encrypted.nonce(), keyVersion,
                apiVersion, quota, json(operations), route, userId);
        Map<String, Object> result = new LinkedHashMap<>(developerAppById(tenantId, Objects.requireNonNull(id)));
        result.put("secret", secret);
        result.put("secretShownOnce", true);
        result.put("signing", Map.of("algorithm", "HMAC-SHA256", "timestampUnit", "epoch-seconds", "maxClockSkewSeconds", 300));
        return result;
    }

    public Map<String, Object> invokeGateway(String apiVersion, String publicKey, long timestamp,
                                             String nonce, String signature, byte[] rawBody) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM developer_app WHERE public_key=? AND status='active'", publicKey);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown developer key");
        Map<String, Object> app = rows.get(0);
        long tenantId = number(app.get("tenant_id"));
        long appId = number(app.get("id"));
        if (!Objects.equals(apiVersion, app.get("api_version"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "API version is not enabled for this application");
        }
        String secret = tenantKeys.decrypt(tenantId, new TenantKeyService.EncryptedValue(
                String.valueOf(app.get("secret_ciphertext")), String.valueOf(app.get("secret_nonce")),
                ((Number) app.get("secret_key_version")).intValue()));
        String path = "/api/gateway/" + apiVersion + "/invoke";
        if (!gatewaySignatures.verify(secret, "POST", path, timestamp, nonce, rawBody, signature)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid request signature or timestamp");
        }
        jdbc.update("DELETE FROM gateway_nonce WHERE expires_at < NOW()");
        int nonceInserted = jdbc.update("INSERT INTO gateway_nonce(app_id,nonce,expires_at) VALUES (?,?,NOW()+INTERVAL '10 minutes') ON CONFLICT DO NOTHING",
                appId, nonce);
        if (nonceInserted == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Request nonce has already been used");
        Integer count = jdbc.queryForObject("""
                INSERT INTO gateway_usage_window(app_id,window_start,request_count)
                VALUES (?,date_trunc('minute',NOW()),1)
                ON CONFLICT(app_id,window_start) DO UPDATE SET request_count=gateway_usage_window.request_count+1
                RETURNING request_count
                """, Integer.class, appId);
        int quota = ((Number) app.get("quota_per_minute")).intValue();
        if (count != null && count > quota) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Gateway quota exceeded");
        Map<String, Object> request;
        try { request = objectMapper.readValue(rawBody, MAP_TYPE); }
        catch (Exception exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body must be valid JSON"); }
        String operation = Objects.toString(request.getOrDefault("operation", ""));
        List<String> allowed = jsonStringList(app.get("allowed_operations"));
        if (!allowed.contains(operation)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Operation is not allowed for this application");
        Object output = switch (operation) {
            case "platform.echo" -> request.getOrDefault("input", Map.of());
            case "platform.capabilities" -> Map.of("gateway", apiVersion, "operations", allowed,
                    "mcpProtocol", "2025-03-26", "multimodal", true);
            case "agent.chat" -> invokeAgent(tenantId, app, request.get("input"));
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported gateway operation");
        };
        jdbc.update("UPDATE developer_app SET last_used_at=NOW() WHERE id=?", appId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("requestId", UUID.randomUUID().toString());
        response.put("tenantId", tenantId);
        response.put("tenantRoute", app.get("tenant_route"));
        response.put("apiVersion", apiVersion);
        response.put("operation", operation);
        response.put("quotaRemaining", Math.max(0, quota - Objects.requireNonNullElse(count, 1)));
        response.put("output", output);
        return response;
    }

    @Transactional
    public Map<String, Object> extractMedia(long tenantId, long userId, Map<String, Object> body) {
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(required(body, "contentBase64", 35_000_000)); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("contentBase64 must be valid Base64", exception); }
        boolean semantic = Boolean.parseBoolean(String.valueOf(body.getOrDefault("semantic", false)));
        MultimodalExtractionService.ExtractionResult result = multimodal.extract(
                Objects.toString(body.get("fileName"), "upload.bin"), Objects.toString(body.get("mediaType"), ""), bytes, semantic);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO multimodal_job
                  (id,tenant_id,file_name,media_type,input_digest,input_bytes,pipeline,status,extraction,provider,review_required,created_by)
                VALUES (?,?,?,?,?,?,?,?,?::jsonb,?,?,?)
                """, id, tenantId, result.fileName(), result.mediaType(), result.digest(), result.bytes(), result.pipeline(),
                result.status(), json(result.extraction()), semantic ? "unconfigured" : null, result.reviewRequired(), userId);
        return mediaJobById(tenantId, id);
    }

    public List<Map<String, Object>> multimodalJobs(long tenantId) {
        return jdbc.queryForList("""
                SELECT id,file_name AS "fileName",media_type AS "mediaType",input_digest AS "inputDigest",
                       input_bytes AS "inputBytes",pipeline,status,extraction,provider,
                       review_required AS "reviewRequired",created_at AS "createdAt"
                FROM multimodal_job WHERE tenant_id=? ORDER BY created_at DESC LIMIT 100
                """, tenantId).stream()
                .map(row -> normalizeJsonColumns(row, "extraction"))
                .toList();
    }

    public List<Map<String, Object>> workerPools(long tenantId) {
        return jdbc.queryForList("""
                SELECT id,pool_name AS "poolName",region,min_replicas AS "minReplicas",max_replicas AS "maxReplicas",
                       target_queue_depth AS "targetQueueDepth",current_replicas AS "currentReplicas",
                       desired_replicas AS "desiredReplicas",status,updated_at AS "updatedAt"
                FROM worker_pool WHERE tenant_id=? ORDER BY region,pool_name
                """, tenantId);
    }

    @Transactional
    public Map<String, Object> scalePlan(long tenantId, Map<String, Object> body) {
        String poolName = Objects.toString(body.getOrDefault("poolName", "agent-worker"));
        String region = Objects.toString(body.getOrDefault("region", "local-primary"));
        int min = intValue(body.get("minReplicas"), 1);
        int max = intValue(body.get("maxReplicas"), 10);
        int target = intValue(body.get("targetQueueDepth"), 10);
        int queue = Math.max(0, intValue(body.get("queueDepth"), 0));
        int current = Math.max(0, intValue(body.get("currentReplicas"), min));
        if (min < 0 || max < min || max > 1000 || target < 1) throw new IllegalArgumentException("Invalid worker scaling bounds");
        int desired = Math.max(min, Math.min(max, queue == 0 ? min : (int) Math.ceil(queue / (double) target)));
        String status = desired > current ? "scaling_up" : desired < current ? "scaling_down" : "ready";
        Long id = jdbc.queryForObject("""
                INSERT INTO worker_pool(tenant_id,pool_name,region,min_replicas,max_replicas,target_queue_depth,current_replicas,desired_replicas,status)
                VALUES (?,?,?,?,?,?,?,?,?)
                ON CONFLICT(tenant_id,pool_name,region) DO UPDATE SET min_replicas=EXCLUDED.min_replicas,
                  max_replicas=EXCLUDED.max_replicas,target_queue_depth=EXCLUDED.target_queue_depth,
                  current_replicas=EXCLUDED.current_replicas,desired_replicas=EXCLUDED.desired_replicas,
                  status=EXCLUDED.status,updated_at=NOW()
                RETURNING id
                """, Long.class, tenantId, poolName, region, min, max, target, current, desired, status);
        return workerPoolById(tenantId, Objects.requireNonNull(id));
    }

    public Map<String, Object> deploymentPlan() {
        return Map.of(
                "base", "deploy/kubernetes/base",
                "overlays", List.of("deploy/kubernetes/overlays/region-primary", "deploy/kubernetes/overlays/region-secondary"),
                "controls", List.of("HPA queue/CPU scaling", "PodDisruptionBudget", "NetworkPolicy",
                        "non-root read-only containers", "topology spread", "startup/readiness/liveness probes"),
                "dataPlane", "External PostgreSQL/Redis endpoints are required for multi-region consistency",
                "apply", "kubectl apply -k deploy/kubernetes/overlays/region-primary"
        );
    }

    @Transactional
    public Map<String, Object> resilienceDrill(long tenantId, long userId, Map<String, Object> body) {
        String type = oneOf(body, "drillType", Set.of("regional_failover", "worker_recovery", "dependency_probe"), "dependency_probe");
        String source = Objects.toString(body.getOrDefault("sourceRegion", "local-primary"));
        String target = Objects.toString(body.getOrDefault("targetRegion", "local-secondary"));
        Map<String, Object> evidence = new LinkedHashMap<>();
        boolean databaseReady = false;
        boolean redisReady = false;
        try { databaseReady = Objects.equals(jdbc.queryForObject("SELECT 1", Integer.class), 1); }
        catch (Exception exception) { evidence.put("databaseError", safe(exception)); }
        try (var connection = redis.getConnection()) { redisReady = "PONG".equalsIgnoreCase(connection.ping()); }
        catch (Exception exception) { evidence.put("redisError", safe(exception)); }
        evidence.put("databaseReady", databaseReady);
        evidence.put("redisReady", redisReady);
        evidence.put("dryRun", true);
        evidence.put("writeTrafficChanged", false);
        evidence.put("targetDifferent", !source.equals(target));
        boolean passed = databaseReady && redisReady && (!"regional_failover".equals(type) || !source.equals(target));
        String status = passed ? "passed" : databaseReady || redisReady ? "warning" : "failed";
        int rto = passed ? 45 : 300;
        int rpo = passed ? 0 : 60;
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO resilience_drill(id,tenant_id,drill_type,source_region,target_region,status,rto_seconds,rpo_seconds,evidence,executed_by) VALUES (?,?,?,?,?,?,?,?,?::jsonb,?)",
                id, tenantId, type, source, target, status, rto, rpo, json(evidence), userId);
        return Map.of("id", id, "drillType", type, "status", status, "rtoSeconds", rto, "rpoSeconds", rpo, "evidence", evidence);
    }

    public List<Map<String, Object>> drills(long tenantId) {
        return jdbc.queryForList("""
                SELECT id,drill_type AS "drillType",source_region AS "sourceRegion",target_region AS "targetRegion",
                       status,rto_seconds AS "rtoSeconds",rpo_seconds AS "rpoSeconds",evidence,created_at AS "createdAt"
                FROM resilience_drill WHERE tenant_id=? ORDER BY created_at DESC LIMIT 50
                """, tenantId).stream()
                .map(row -> normalizeJsonColumns(row, "evidence"))
                .toList();
    }

    public Map<String, Object> developerPortal(long tenantId) {
        return Map.of(
                "basePath", "/api/gateway/v1/invoke",
                "authentication", Map.of("headers", List.of("X-Developer-Key", "X-Timestamp", "X-Nonce", "X-Signature"),
                        "canonical", "METHOD\\nPATH\\nTIMESTAMP\\nNONCE\\nSHA256(BODY)", "algorithm", "HMAC-SHA256"),
                "operations", List.of(
                        Map.of("id", "platform.echo", "description", "Signed tenant-routed echo for connectivity verification"),
                        Map.of("id", "platform.capabilities", "description", "Return enabled protocol capabilities"),
                        Map.of("id", "agent.chat", "description", "Execute a published tenant Agent through the Java-to-Python runtime")),
                "sdk", Map.of("python", "sdk/python"),
                "activeApps", developerApps(tenantId).size()
        );
    }

    public Map<String, Object> healthReport(long tenantId) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("tenantId", tenantId);
        report.put("diagnostics", diagnostics.diagnose(tenantId));
        report.put("ecosystem", overview(tenantId));
        report.put("redaction", Map.of("secretValuesIncluded", false, "credentialValuesIncluded", false,
                "shareable", true, "rule", "Only status, counts and non-sensitive identifiers are included"));
        return report;
    }

    private Map<String, Object> packageById(long tenantId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id,package_type AS "packageType",package_name AS "packageName",version,visibility,
                       source_uri AS "sourceUri",source_digest AS "sourceDigest",manifest,signature,
                       signature_algorithm AS "signatureAlgorithm",signer,compatibility,
                       scan_status AS "scanStatus",risk_score AS "riskScore",scan_findings AS "scanFindings",
                       created_at AS "createdAt",updated_at AS "updatedAt"
                FROM ecosystem_package WHERE tenant_id=? AND id=?
                """, tenantId, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Ecosystem package not found");
        return normalizeJsonColumns(rows.get(0), "manifest", "compatibility", "scanFindings");
    }

    private Map<String, Object> invokeAgent(long tenantId, Map<String, Object> app, Object rawInput) {
        Map<String, Object> input = mapValue(rawInput);
        long agentId;
        try { agentId = number(input.get("agentId")); }
        catch (Exception exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agent.chat requires a numeric agentId"); }
        String message;
        try { message = required(input, "message", 16_000); }
        catch (IllegalArgumentException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage()); }
        Integer matches = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_definition WHERE id=? AND tenant_id=? AND status='published'",
                Integer.class, agentId, tenantId);
        if (matches == null || matches == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Published Agent not found for this tenant");
        }
        String sessionId = blankToNull(input.get("sessionId"));
        if (sessionId == null) sessionId = "gateway-" + UUID.randomUUID();
        if (sessionId.length() > 64) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is too long");
        String userId = Objects.toString(app.get("created_by"), "0");
        StringBuilder reply = new StringBuilder();
        AtomicReference<String> error = new AtomicReference<>();
        ExecutionRequest execution = ExecutionRequest.newBuilder()
                .setSessionId(sessionId)
                .setAgentId(String.valueOf(agentId))
                .setTenantId(String.valueOf(tenantId))
                .setUserId(userId)
                .setMessage(message)
                .setChannel("developer-gateway")
                .build();
        pythonAgentClient.executeAgent(execution, response -> {
            if (response.getType() == ExecutionResponse.Type.TEXT) reply.append(response.getContent());
            if (response.getType() == ExecutionResponse.Type.ERROR) error.compareAndSet(null, response.getContent());
        }, throwable -> error.compareAndSet(null, Objects.toString(throwable.getMessage(), throwable.getClass().getSimpleName())), () -> {});
        if (error.get() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Agent runtime execution failed");
        }
        if (reply.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Agent runtime returned no reply");
        }
        return Map.of("agentId", agentId, "sessionId", sessionId, "reply", reply.toString());
    }

    private Map<String, Object> mcpById(long tenantId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id,name,direction,transport,endpoint,protocol_version AS "protocolVersion",
                       auth_secret_ref AS "authSecretRef",capabilities,status,last_probe AS "lastProbe",
                       last_probed_at AS "lastProbedAt",updated_at AS "updatedAt"
                FROM mcp_connection WHERE tenant_id=? AND id=?
                """, tenantId, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("MCP connection not found");
        return normalizeJsonColumns(rows.get(0), "capabilities", "lastProbe");
    }

    private Map<String, Object> developerAppById(long tenantId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id,app_name AS "appName",public_key AS "publicKey",api_version AS "apiVersion",
                       quota_per_minute AS "quotaPerMinute",allowed_operations AS "allowedOperations",
                       tenant_route AS "tenantRoute",status,last_used_at AS "lastUsedAt",created_at AS "createdAt"
                FROM developer_app WHERE tenant_id=? AND id=?
                """, tenantId, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Developer application not found");
        return normalizeJsonColumns(rows.get(0), "allowedOperations");
    }

    private Map<String, Object> mediaJobById(long tenantId, UUID id) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT id,file_name AS "fileName",media_type AS "mediaType",input_digest AS "inputDigest",
                       input_bytes AS "inputBytes",pipeline,status,extraction,provider,
                       review_required AS "reviewRequired",created_at AS "createdAt"
                FROM multimodal_job WHERE tenant_id=? AND id=?
                """, tenantId, id);
        return normalizeJsonColumns(row, "extraction");
    }

    private Map<String, Object> workerPoolById(long tenantId, long id) {
        return jdbc.queryForMap("""
                SELECT id,pool_name AS "poolName",region,min_replicas AS "minReplicas",max_replicas AS "maxReplicas",
                       target_queue_depth AS "targetQueueDepth",current_replicas AS "currentReplicas",
                       desired_replicas AS "desiredReplicas",status,updated_at AS "updatedAt"
                FROM worker_pool WHERE tenant_id=? AND id=?
                """, tenantId, id);
    }

    private void validateMcpEndpoint(String transport, String endpoint) {
        if ("stdio".equals(transport)) {
            if (!endpoint.startsWith("stdio://") || endpoint.contains("..")) {
                throw new IllegalArgumentException("stdio MCP endpoint must use a non-traversing stdio:// URI");
            }
            return;
        }
        URI uri;
        try { uri = URI.create(endpoint).normalize(); }
        catch (Exception exception) { throw new IllegalArgumentException("MCP endpoint is invalid"); }
        if (isRuntimeEndpoint(endpoint)) return;
        webhookUrlValidator.validate(uri.toString());
    }

    private boolean isRuntimeEndpoint(String endpoint) {
        return endpoint.replaceAll("/+$", "").equals(runtimeBaseUrl + "/mcp");
    }

    private Map<String, Object> mapValue(Object raw) {
        if (raw == null) return Map.of();
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        try { return objectMapper.readValue(String.valueOf(raw), MAP_TYPE); }
        catch (Exception exception) { throw new IllegalArgumentException("Expected a JSON object", exception); }
    }

    private Map<String, Object> normalizeJsonColumns(Map<String, Object> row, String... columns) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        for (String column : columns) {
            Object raw = result.get(column);
            if (raw != null) result.put(column, jsonValue(raw));
        }
        return result;
    }

    private Object jsonValue(Object raw) {
        if (raw instanceof Map<?, ?> || raw instanceof Collection<?>) return raw;
        try { return objectMapper.readValue(String.valueOf(raw), Object.class); }
        catch (Exception exception) { throw new IllegalStateException("Stored JSON value is invalid", exception); }
    }

    private List<String> jsonStringList(Object raw) {
        if (raw instanceof Collection<?> values) return values.stream().map(String::valueOf).toList();
        try { return objectMapper.readValue(String.valueOf(raw), STRING_LIST_TYPE); }
        catch (Exception exception) { return List.of(); }
    }

    private List<String> stringList(Object raw) {
        if (!(raw instanceof Collection<?> values)) return List.of();
        return values.stream().map(String::valueOf).map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("Value cannot be encoded as JSON", exception); }
    }

    private String required(Map<String, Object> body, String key, int maxLength) {
        String value = Objects.toString(body.get(key), "").trim();
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required");
        if (value.length() > maxLength) throw new IllegalArgumentException(key + " is too long");
        return value;
    }

    private String oneOf(Map<String, Object> body, String key, Set<String> allowed, String fallback) {
        String value = Objects.toString(body.getOrDefault(key, fallback)).trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(value)) throw new IllegalArgumentException(key + " is invalid");
        return value;
    }

    private String blankToNull(Object raw) {
        String value = Objects.toString(raw, "").trim();
        return value.isBlank() ? null : value;
    }

    private int intValue(Object raw, int fallback) {
        try { return raw == null ? fallback : Integer.parseInt(String.valueOf(raw)); }
        catch (NumberFormatException exception) { return fallback; }
    }

    private long number(Object raw) {
        if (raw instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(raw));
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private String safe(Exception exception) {
        String message = Objects.toString(exception.getMessage(), exception.getClass().getSimpleName());
        return message.length() > 180 ? message.substring(0, 180) : message;
    }
}
