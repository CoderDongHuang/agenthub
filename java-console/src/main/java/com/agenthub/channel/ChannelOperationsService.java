package com.agenthub.channel;

import com.agenthub.grpc.PythonAgentClient;
import com.agenthub.grpc.stub.ExecutionRequest;
import com.agenthub.grpc.stub.ExecutionResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ChannelOperationsService {
    private static final Set<String> CHANNELS = Set.of("feishu", "dingtalk", "wechat", "api", "web", "custom");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PythonAgentClient runtime;
    private final RestClient http = RestClient.create();
    private final long defaultAgentId;
    private final String feishuAppId;
    private final String feishuAppSecret;
    private final String wechatCorpId;
    private final String wechatSecret;
    private final int wechatAgentId;
    private final String dingWebhook;
    private final String dingSecret;

    public ChannelOperationsService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PythonAgentClient runtime,
            @Value("${FEISHU_APP_ID:}") String feishuAppId,
            @Value("${FEISHU_APP_SECRET:}") String feishuAppSecret,
            @Value("${WECHAT_CORP_ID:}") String wechatCorpId,
            @Value("${WECHAT_APP_SECRET:}") String wechatSecret,
            @Value("${WECHAT_AGENT_ID:0}") int wechatAgentId,
            @Value("${DINGTALK_WEBHOOK_URL:}") String dingWebhook,
            @Value("${DINGTALK_SIGN_SECRET:}") String dingSecret) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.runtime = runtime;
        this.defaultAgentId = -1;
        this.feishuAppId = feishuAppId;
        this.feishuAppSecret = feishuAppSecret;
        this.wechatCorpId = wechatCorpId;
        this.wechatSecret = wechatSecret;
        this.wechatAgentId = wechatAgentId;
        this.dingWebhook = dingWebhook;
        this.dingSecret = dingSecret;
    }

    public long resolveTenant(String channel, String externalAccountId) {
        if (externalAccountId == null || externalAccountId.isBlank()) {
            throw new SecurityException("channel account identifier is required");
        }
        List<Long> tenants = jdbc.queryForList(
                "SELECT tenant_id FROM channel_binding WHERE channel=? AND external_account_id=? AND enabled=TRUE",
                Long.class, channel, externalAccountId);
        if (tenants.size() != 1) throw new SecurityException("active channel binding not found");
        return tenants.getFirst();
    }

    @Transactional
    public Map<String, Object> handleInbound(InboundMessage message) {
        requireChannel(message.channel());
        if (message.externalMessageId() == null || message.externalMessageId().isBlank()) {
            throw new IllegalArgumentException("externalMessageId is required");
        }
        List<UUID> inserted = jdbc.query(
                """
                INSERT INTO channel_delivery(id,tenant_id,channel,direction,external_message_id,conversation_key,
                    recipient_id,status,payload,max_attempts)
                VALUES (?,?,?,?,?,?,?,'accepted',?::jsonb,1)
                ON CONFLICT(tenant_id,channel,external_message_id,direction) DO NOTHING RETURNING id
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class), UUID.randomUUID(), message.tenantId(), message.channel(),
                "inbound", message.externalMessageId(), message.conversationKey(), message.senderId(), json(message));
        if (inserted.isEmpty()) {
            Map<String, Object> existing = jdbc.queryForMap(
                    "SELECT id,status,response_payload FROM channel_delivery WHERE tenant_id=? AND channel=? AND external_message_id=? AND direction='inbound'",
                    message.tenantId(), message.channel(), message.externalMessageId());
            return Map.of("duplicate", true, "deliveryId", existing.get("id"), "status", existing.get("status"));
        }

        return Map.of("duplicate", false, "deliveryId", inserted.getFirst(), "status", "accepted");
    }

    @Scheduled(fixedDelayString = "${agenthub.channel.retry-interval-ms:5000}", initialDelay = 5000)
    public void processInboundDeliveries() {
        String workerId = UUID.randomUUID().toString();
        jdbc.update("UPDATE channel_delivery SET status='retrying',worker_id=NULL,lease_expires_at=NULL,last_error='Inbound worker lease expired',updated_at=NOW() WHERE direction='inbound' AND status='processing' AND lease_expires_at<NOW()");
        List<Map<String, Object>> claimed = jdbc.queryForList("""
                WITH candidates AS (
                    SELECT id FROM channel_delivery WHERE direction='inbound' AND status IN ('accepted','retrying')
                    ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 20
                )
                UPDATE channel_delivery delivery SET status='processing',worker_id=?,lease_expires_at=NOW()+INTERVAL '5 minutes',
                    attempt_count=attempt_count+1,updated_at=NOW() FROM candidates WHERE delivery.id=candidates.id
                RETURNING delivery.id,delivery.payload::text AS payload
                """, workerId);
        for (Map<String, Object> row : claimed) {
            UUID deliveryId = (UUID) row.get("id");
            try {
                InboundMessage message = mapper.convertValue(parse(row.get("payload")), InboundMessage.class);
                processInbound(deliveryId, message);
            } catch (Exception exception) {
                jdbc.update("UPDATE channel_delivery SET status=CASE WHEN attempt_count>=max_attempts THEN 'dead_letter' ELSE 'retrying' END,last_error=?,worker_id=NULL,lease_expires_at=NULL,updated_at=NOW() WHERE id=? AND worker_id=?",
                        safe(exception), deliveryId, workerId);
            }
        }
    }

    private void processInbound(UUID deliveryId, InboundMessage message) {
        long agentId = resolveAgent(message.tenantId(), message.channel(), message.chatType(), message.text());
        Map<String, Object> conversation = resolveConversation(message, agentId);
        String cleanedText = stripMention(message.text());
        jdbc.update("UPDATE channel_delivery SET agent_id=?,updated_at=NOW() WHERE id=?",
                agentId, deliveryId);

        StringBuilder reply = new StringBuilder();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutionRequest request = ExecutionRequest.newBuilder()
                .setSessionId(String.valueOf(conversation.get("session_id")))
                .setAgentId(String.valueOf(agentId))
                .setUserId(message.senderId())
                .setTenantId(String.valueOf(message.tenantId()))
                .setMessage(cleanedText)
                .setChannel(message.channel())
                .build();
        runtime.executeAgent(request, response -> {
            if (response.getType() == ExecutionResponse.Type.TEXT) reply.append(response.getContent());
        }, failure::set, () -> { });

        if (failure.get() != null) {
            String error = safe(failure.get());
            throw new IllegalStateException(error, failure.get());
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reply", reply.toString());
        response.put("sessionId", conversation.get("session_id"));
        response.put("agentId", agentId);
        jdbc.update("UPDATE channel_delivery SET status='delivered',response_payload=?::jsonb,receipt_at=NOW(),updated_at=NOW() WHERE id=?",
                json(response), deliveryId);

        if (!reply.isEmpty()) {
            enqueueAndDeliver(message.tenantId(), message.channel(), message.externalMessageId() + ":reply",
                    message.conversationKey(), message.replyTarget().isBlank() ? message.senderId() : message.replyTarget(), agentId,
                    Map.of("text", reply.toString(), "card", false, "title", "AgentHub"), null);
        }
    }

    public Map<String, Object> enqueueAndDeliver(long tenantId, String channel, String externalMessageId,
                                                  String conversationKey, String recipientId, long agentId,
                                                  Map<String, Object> payload, UUID replayedFrom) {
        requireChannel(channel);
        Integer agentCount = jdbc.queryForObject("SELECT COUNT(*) FROM agent_definition WHERE id=? AND tenant_id=?", Integer.class, agentId, tenantId);
        if (agentCount == null || agentCount == 0) throw new IllegalArgumentException("Agent not found in current tenant");
        UUID id = UUID.randomUUID();
        List<UUID> inserted = jdbc.query(
                """
                INSERT INTO channel_delivery(id,tenant_id,channel,direction,external_message_id,conversation_key,
                    agent_id,recipient_id,status,payload,replayed_from)
                VALUES (?,?,?,?,?,?,?,?,'accepted',?::jsonb,?)
                ON CONFLICT(tenant_id,channel,external_message_id,direction) DO NOTHING RETURNING id
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), id, tenantId, channel, "outbound",
                externalMessageId, conversationKey, agentId, recipientId, json(payload), replayedFrom);
        UUID actualId = inserted.isEmpty() ? jdbc.queryForObject(
                "SELECT id FROM channel_delivery WHERE tenant_id=? AND channel=? AND external_message_id=? AND direction='outbound'",
                UUID.class, tenantId, channel, externalMessageId) : id;
        if (inserted.isEmpty()) return delivery(tenantId, Objects.requireNonNull(actualId));
        deliver(actualId);
        return delivery(tenantId, actualId);
    }

    public Map<String, Object> replay(long tenantId, UUID id) {
        Map<String, Object> original = delivery(tenantId, id);
        if (!"dead_letter".equals(original.get("status"))) {
            throw new IllegalStateException("Only dead-letter deliveries can be replayed");
        }
        return enqueueAndDeliver(tenantId, text(original.get("channel")),
                text(original.get("externalMessageId")) + ":replay:" + UUID.randomUUID(),
                text(original.get("conversationKey")), text(original.get("recipientId")),
                number(original.get("agentId"), defaultAgentId), asMap(original.get("payload")), id);
    }

    @Scheduled(fixedDelayString = "${agenthub.channel.retry-interval-ms:5000}", initialDelay = 10000)
    public void retryDueDeliveries() {
        jdbc.update("UPDATE channel_delivery SET status='retrying',next_attempt_at=NOW(),last_error='Delivery worker interrupted',updated_at=NOW() WHERE direction='outbound' AND status='processing' AND updated_at<NOW()-INTERVAL '5 minutes'");
        List<UUID> ids = jdbc.query(
                "SELECT id FROM channel_delivery WHERE direction='outbound' AND status='retrying' AND next_attempt_at<=NOW() ORDER BY next_attempt_at LIMIT 50",
                (rs, rowNum) -> rs.getObject("id", UUID.class));
        ids.forEach(this::deliver);
    }

    public Map<String, Object> overview(long tenantId) {
        Map<String, Object> counts = new LinkedHashMap<>();
        jdbc.queryForList("SELECT status,COUNT(*) AS count FROM channel_delivery WHERE tenant_id=? GROUP BY status", tenantId)
                .forEach(row -> counts.put(text(row.get("status")), row.get("count")));
        Long conversations = jdbc.queryForObject("SELECT COUNT(*) FROM channel_conversation WHERE tenant_id=?", Long.class, tenantId);
        Long routes = jdbc.queryForObject("SELECT COUNT(*) FROM channel_route_rule WHERE tenant_id=? AND enabled=TRUE", Long.class, tenantId);
        return Map.of("counts", counts, "conversations", Objects.requireNonNullElse(conversations, 0L),
                "activeRoutes", Objects.requireNonNullElse(routes, 0L));
    }

    public List<Map<String, Object>> deliveries(long tenantId, String status) {
        String filter = status == null || status.isBlank() ? "" : " AND status=?";
        Object[] args = filter.isBlank() ? new Object[]{tenantId} : new Object[]{tenantId, status};
        return jdbc.queryForList("""
                SELECT id,channel,direction,external_message_id AS "externalMessageId",conversation_key AS "conversationKey",
                       agent_id AS "agentId",recipient_id AS "recipientId",status,payload,response_payload AS "responsePayload",
                       attempt_count AS "attemptCount",max_attempts AS "maxAttempts",next_attempt_at AS "nextAttemptAt",
                       last_error AS "lastError",receipt_at AS "receiptAt",replayed_from AS "replayedFrom",created_at AS "createdAt"
                FROM channel_delivery WHERE tenant_id=?
                """ + filter + " ORDER BY created_at DESC LIMIT 200", args).stream()
                .map(row -> normalizeJson(row, "payload", "responsePayload")).toList();
    }

    public List<Map<String, Object>> conversations(long tenantId) {
        return jdbc.queryForList("""
                SELECT id,channel,conversation_key AS "conversationKey",session_id AS "sessionId",agent_id AS "agentId",
                       context,last_message_at AS "lastMessageAt",created_at AS "createdAt"
                FROM channel_conversation WHERE tenant_id=? ORDER BY last_message_at DESC LIMIT 200
                """, tenantId).stream().map(row -> normalizeJson(row, "context")).toList();
    }

    public List<Map<String, Object>> routes(long tenantId) {
        return jdbc.queryForList("""
                SELECT id,name,channel,chat_type AS "chatType",match_type AS "matchType",match_value AS "matchValue",
                       agent_id AS "agentId",priority,enabled,created_at AS "createdAt",updated_at AS "updatedAt"
                FROM channel_route_rule WHERE tenant_id=? ORDER BY priority,id
                """, tenantId);
    }

    public List<Map<String, Object>> bindings(long tenantId) {
        return jdbc.queryForList("""
                SELECT id,channel,external_account_id AS "externalAccountId",agent_id AS "agentId",enabled,
                       created_at AS "createdAt",updated_at AS "updatedAt"
                FROM channel_binding WHERE tenant_id=? ORDER BY channel,id
                """, tenantId);
    }

    public Map<String, Object> saveBinding(long tenantId, Map<String, Object> body) {
        String channel = oneOf(required(body, "channel"), Set.of("feishu", "dingtalk", "wechat"));
        String externalAccountId = required(body, "externalAccountId");
        long agentId = number(body.get("agentId"), -1);
        if (agentId < 1) throw new IllegalArgumentException("agentId is required");
        Integer agents = jdbc.queryForObject("SELECT COUNT(*) FROM agent_definition WHERE id=? AND tenant_id=?", Integer.class, agentId, tenantId);
        if (agents == null || agents == 0) throw new IllegalArgumentException("Agent not found in current tenant");
        List<Long> owners = jdbc.queryForList("SELECT tenant_id FROM channel_binding WHERE channel=? AND external_account_id=?", Long.class, channel, externalAccountId);
        if (!owners.isEmpty() && owners.getFirst() != tenantId) throw new SecurityException("Channel account is owned by another tenant");
        Long id = jdbc.queryForObject("""
                INSERT INTO channel_binding(tenant_id,channel,external_account_id,agent_id,enabled)
                VALUES (?,?,?,?,?) ON CONFLICT(channel,external_account_id) DO UPDATE SET
                    tenant_id=EXCLUDED.tenant_id,agent_id=EXCLUDED.agent_id,enabled=EXCLUDED.enabled,updated_at=NOW()
                RETURNING id
                """, Long.class, tenantId, channel, externalAccountId, agentId, !Boolean.FALSE.equals(body.get("enabled")));
        return bindings(tenantId).stream().filter(item -> Objects.equals(item.get("id"), id)).findFirst().orElseThrow();
    }

    public Map<String, Object> saveRoute(long tenantId, Map<String, Object> body) {
        String name = required(body, "name");
        String channel = text(body.getOrDefault("channel", "*"));
        String chatType = text(body.getOrDefault("chatType", "*"));
        String matchType = oneOf(text(body.getOrDefault("matchType", "default")), Set.of("default", "mention", "keyword"));
        String matchValue = text(body.get("matchValue"));
        long agentId = number(body.get("agentId"), -1);
        if (agentId < 1) throw new IllegalArgumentException("agentId is required");
        Integer agentCount = jdbc.queryForObject("SELECT COUNT(*) FROM agent_definition WHERE id=? AND tenant_id=?", Integer.class, agentId, tenantId);
        if (agentCount == null || agentCount == 0) throw new IllegalArgumentException("Agent not found in current tenant");
        int priority = (int) number(body.get("priority"), 100);
        boolean enabled = !Boolean.FALSE.equals(body.get("enabled"));
        Long id = jdbc.queryForObject("""
                INSERT INTO channel_route_rule(tenant_id,name,channel,chat_type,match_type,match_value,agent_id,priority,enabled)
                VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(tenant_id,name) DO UPDATE SET channel=EXCLUDED.channel,
                chat_type=EXCLUDED.chat_type,match_type=EXCLUDED.match_type,match_value=EXCLUDED.match_value,
                agent_id=EXCLUDED.agent_id,priority=EXCLUDED.priority,enabled=EXCLUDED.enabled,updated_at=NOW() RETURNING id
                """, Long.class, tenantId, name, channel, chatType, matchType, matchValue, agentId, priority, enabled);
        return routes(tenantId).stream().filter(route -> Objects.equals(route.get("id"), id)).findFirst().orElseThrow();
    }

    public Map<String, Object> cardTemplates(String title, String text) {
        return Map.of(
                "feishu", Map.of("msg_type", "interactive", "card", Map.of(
                        "header", Map.of("title", Map.of("tag", "plain_text", "content", title)),
                        "elements", List.of(Map.of("tag", "markdown", "content", text)))),
                "dingtalk", Map.of("msgtype", "actionCard", "actionCard", Map.of(
                        "title", title, "text", "### " + title + "\n\n" + text, "btnOrientation", "0", "btns", List.of())),
                "wechat", Map.of("msgtype", "textcard", "textcard", Map.of(
                        "title", title, "description", text, "url", "http://localhost:5173", "btntxt", "Open")));
    }

    private Map<String, Object> resolveConversation(InboundMessage message, long agentId) {
        UUID id = UUID.randomUUID();
        String sessionId = message.channel() + "-" + UUID.randomUUID();
        Map<String, Object> context = Map.of("senderId", message.senderId(), "chatType", message.chatType(),
                "channel", message.channel());
        return jdbc.queryForMap("""
                INSERT INTO channel_conversation(id,tenant_id,channel,conversation_key,session_id,agent_id,context)
                VALUES (?,?,?,?,?,?,?::jsonb) ON CONFLICT(tenant_id,channel,conversation_key) DO UPDATE
                SET agent_id=EXCLUDED.agent_id,context=channel_conversation.context || EXCLUDED.context,
                    last_message_at=NOW(),updated_at=NOW()
                RETURNING id,session_id,agent_id,context
                """, id, message.tenantId(), message.channel(), message.conversationKey(), sessionId, agentId, json(context));
    }

    private long resolveAgent(long tenantId, String channel, String chatType, String message) {
        List<Map<String, Object>> rules = jdbc.queryForList("""
                SELECT agent_id,match_type,match_value FROM channel_route_rule
                WHERE tenant_id=? AND enabled=TRUE AND (channel='*' OR channel=?) AND (chat_type='*' OR chat_type=?)
                ORDER BY priority,id
                """, tenantId, channel, chatType);
        String lower = text(message).toLowerCase(Locale.ROOT);
        for (Map<String, Object> rule : rules) {
            String type = text(rule.get("match_type"));
            String value = text(rule.get("match_value")).toLowerCase(Locale.ROOT);
            if ("default".equals(type) || (!value.isBlank() && lower.contains(value))) {
                return ((Number) rule.get("agent_id")).longValue();
            }
        }
        List<Long> boundAgents = jdbc.queryForList(
                "SELECT agent_id FROM channel_binding WHERE tenant_id=? AND channel=? AND enabled=TRUE ORDER BY id LIMIT 1",
                Long.class, tenantId, channel);
        if (boundAgents.isEmpty()) throw new IllegalStateException("No Agent is bound to this channel account");
        return boundAgents.getFirst();
    }

    private void deliver(UUID id) {
        List<Map<String, Object>> claimed = jdbc.queryForList("""
                UPDATE channel_delivery SET status='processing',updated_at=NOW()
                WHERE id=? AND direction='outbound' AND status IN ('accepted','retrying')
                RETURNING channel,recipient_id,payload::text AS payload,attempt_count,max_attempts
                """, id);
        if (claimed.isEmpty()) return;
        Map<String, Object> row = claimed.getFirst();
        int attempt = ((Number) row.get("attempt_count")).intValue() + 1;
        try {
            send(text(row.get("channel")), text(row.get("recipient_id")), asMap(parse(row.get("payload"))));
            jdbc.update("UPDATE channel_delivery SET status='delivered',attempt_count=?,receipt_at=NOW(),next_attempt_at=NULL,last_error=NULL,updated_at=NOW() WHERE id=?",
                    attempt, id);
        } catch (Exception exception) {
            int maxAttempts = ((Number) row.get("max_attempts")).intValue();
            String status = attempt >= maxAttempts ? "dead_letter" : "retrying";
            long delaySeconds = backoffSeconds(attempt);
            jdbc.update("UPDATE channel_delivery SET status=?,attempt_count=?,next_attempt_at=CASE WHEN ?='retrying' THEN NOW()+(? * INTERVAL '1 second') ELSE NULL END,last_error=?,updated_at=NOW() WHERE id=?",
                    status, attempt, status, delaySeconds, safe(exception), id);
        }
    }

    private void send(String channel, String recipient, Map<String, Object> payload) throws Exception {
        String text = text(payload.get("text"));
        String title = text(payload.getOrDefault("title", "AgentHub"));
        boolean card = Boolean.TRUE.equals(payload.get("card"));
        switch (channel) {
            case "feishu" -> sendFeishu(recipient, title, text, card);
            case "dingtalk" -> sendDing(recipient, title, text, card);
            case "wechat" -> sendWechat(recipient, title, text, card);
            default -> throw new IllegalArgumentException("No outbound sender configured for channel: " + channel);
        }
    }

    private void sendFeishu(String recipient, String title, String text, boolean card) throws Exception {
        if (feishuAppId.isBlank() || feishuAppSecret.isBlank()) throw new IllegalStateException("Feishu credentials are not configured");
        Map<?, ?> token = http.post().uri("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal")
                .body(Map.of("app_id", feishuAppId, "app_secret", feishuAppSecret)).retrieve().body(Map.class);
        if (token != null && number(token.get("code"), 0) != 0) {
            throw new IllegalStateException("Feishu token request failed: " + text(token.get("msg")));
        }
        String access = token == null ? "" : Objects.toString(token.get("tenant_access_token"), "");
        if (access.isBlank()) throw new IllegalStateException("Feishu access token is unavailable");
        Map<String, Object> template = cardTemplates(title, text);
        Object content = card ? template.get("feishu") : Map.of("msg_type", "text", "content", Map.of("text", text));
        Map<String, Object> body = new LinkedHashMap<>(asMap(content));
        body.put("receive_id", recipient);
        if (body.containsKey("card")) body.put("content", mapper.writeValueAsString(body.remove("card")));
        else body.put("content", mapper.writeValueAsString(body.get("content")));
        Map<?, ?> response = http.post().uri("https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=open_id")
                .header("Authorization", "Bearer " + access).body(body).retrieve().body(Map.class);
        if (response == null || number(response.get("code"), -1) != 0) {
            throw new IllegalStateException("Feishu message rejected: " + (response == null ? "empty response" : text(response.get("msg"))));
        }
    }

    private void sendDing(String recipient, String title, String text, boolean card) {
        String webhook = recipient.startsWith("https://") ? recipient : dingWebhook;
        if (webhook.isBlank()) throw new IllegalStateException("DingTalk webhook is not configured");
        Object body = card ? cardTemplates(title, text).get("dingtalk")
                : Map.of("msgtype", "text", "text", Map.of("content", text));
        String url = webhook;
        if (!dingSecret.isBlank() && webhook.equals(dingWebhook)) {
            try {
                long timestamp = Instant.now().toEpochMilli();
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(dingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                String signature = Base64.getEncoder().encodeToString(
                        mac.doFinal((timestamp + "\n" + dingSecret).getBytes(StandardCharsets.UTF_8)));
                url += (url.contains("?") ? "&" : "?") + "timestamp=" + timestamp + "&sign="
                        + URLEncoder.encode(signature, StandardCharsets.UTF_8);
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to sign DingTalk webhook request", exception);
            }
        }
        Map<?, ?> response = http.post().uri(url).body(body).retrieve().body(Map.class);
        if (response == null || number(response.get("errcode"), -1) != 0) {
            throw new IllegalStateException("DingTalk message rejected: " + (response == null ? "empty response" : text(response.get("errmsg"))));
        }
    }

    private void sendWechat(String recipient, String title, String text, boolean card) {
        if (wechatCorpId.isBlank() || wechatSecret.isBlank() || wechatAgentId < 1) throw new IllegalStateException("WeCom credentials are not configured");
        Map<?, ?> token = http.get().uri("https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid={corp}&corpsecret={secret}", wechatCorpId, wechatSecret)
                .retrieve().body(Map.class);
        String access = token == null ? "" : Objects.toString(token.get("access_token"), "");
        if (access.isBlank()) throw new IllegalStateException("WeCom access token is unavailable");
        Map<String, Object> body = new LinkedHashMap<>(asMap(card ? cardTemplates(title, text).get("wechat")
                : Map.of("msgtype", "text", "text", Map.of("content", text))));
        body.put("touser", recipient);
        body.put("agentid", wechatAgentId);
        Map<?, ?> response = http.post().uri("https://qyapi.weixin.qq.com/cgi-bin/message/send?access_token={token}", access)
                .body(body).retrieve().body(Map.class);
        if (response == null || number(response.get("errcode"), -1) != 0) {
            throw new IllegalStateException("WeCom message rejected: " + (response == null ? "empty response" : text(response.get("errmsg"))));
        }
    }

    private Map<String, Object> delivery(long tenantId, UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id,channel,direction,external_message_id AS "externalMessageId",conversation_key AS "conversationKey",
                       agent_id AS "agentId",recipient_id AS "recipientId",status,payload,response_payload AS "responsePayload",
                       attempt_count AS "attemptCount",max_attempts AS "maxAttempts",next_attempt_at AS "nextAttemptAt",
                       last_error AS "lastError",receipt_at AS "receiptAt",replayed_from AS "replayedFrom",created_at AS "createdAt"
                FROM channel_delivery WHERE tenant_id=? AND id=?
                """, tenantId, id);
        if (rows.isEmpty()) throw new NoSuchElementException("Channel delivery not found");
        return normalizeJson(rows.getFirst(), "payload", "responsePayload");
    }

    static long backoffSeconds(int attempt) {
        int exponent = Math.max(0, Math.min(attempt - 1, 10));
        return Math.min(3600, 5L * (1L << exponent));
    }

    static String stripMention(String message) {
        return text(message).replaceAll("(?i)@[\\p{L}\\p{N}_-]+", "").trim();
    }

    private Map<String, Object> normalizeJson(Map<String, Object> row, String... fields) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        for (String field : fields) result.put(field, parse(row.get(field)));
        return result;
    }

    private void requireChannel(String channel) {
        if (!CHANNELS.contains(channel)) throw new IllegalArgumentException("Unsupported channel: " + channel);
    }

    private String oneOf(String value, Set<String> allowed) {
        if (!allowed.contains(value)) throw new IllegalArgumentException("Unsupported value: " + value);
        return value;
    }

    private String required(Map<String, Object> body, String key) {
        String value = text(body.get(key));
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private long number(Object value, long fallback) {
        if (value == null || text(value).isBlank()) return fallback;
        return value instanceof Number number ? number.longValue() : Long.parseLong(text(value));
    }
    private String safe(Throwable error) {
        String value = error.getMessage() == null ? error.getClass().getSimpleName() : error.getClass().getSimpleName() + ": " + error.getMessage();
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception exception) { throw new IllegalArgumentException("Unable to serialize channel payload", exception); }
    }
    private Object parse(Object value) {
        if (value == null || value instanceof Map<?, ?> || value instanceof Collection<?>) return value;
        try { return mapper.readValue(String.valueOf(value), Object.class); }
        catch (Exception ignored) { return Map.of(); }
    }
    private Map<String, Object> asMap(Object value) {
        if (value == null) return Map.of();
        return mapper.convertValue(value, new TypeReference<>() { });
    }

    public record InboundMessage(long tenantId, String channel, String externalMessageId, String conversationKey,
                                 String senderId, String chatType, String text, String replyTarget,
                                 Map<String, Object> payload) {
        public InboundMessage {
            replyTarget = replyTarget == null ? "" : replyTarget;
        }
    }
}
