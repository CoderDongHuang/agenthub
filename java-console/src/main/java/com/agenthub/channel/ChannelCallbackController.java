package com.agenthub.channel;

import com.agenthub.common.response.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/channel")
public class ChannelCallbackController {
    private static final Logger log = LoggerFactory.getLogger(ChannelCallbackController.class);
    private final ObjectMapper mapper;
    private final ChannelOperationsService operations;
    private final String feishuToken, feishuEncryptKey;
    private final String wechatToken, wechatAesKey, wechatCorpId;
    private final String dingSecret;

    public ChannelCallbackController(ObjectMapper mapper, ChannelOperationsService operations,
            @Value("${FEISHU_VERIFICATION_TOKEN:}") String feishuToken,
            @Value("${FEISHU_ENCRYPT_KEY:}") String feishuEncryptKey,
            @Value("${WECHAT_TOKEN:}") String wechatToken,
            @Value("${WECHAT_ENCODING_AES_KEY:}") String wechatAesKey,
            @Value("${WECHAT_CORP_ID:}") String wechatCorpId,
            @Value("${DINGTALK_SIGN_SECRET:}") String dingSecret) {
        this.mapper=mapper; this.operations=operations;
        this.feishuToken=feishuToken; this.feishuEncryptKey=feishuEncryptKey;
        this.wechatToken=wechatToken; this.wechatAesKey=wechatAesKey; this.wechatCorpId=wechatCorpId;
        this.dingSecret=dingSecret;
    }

    @PostMapping(value="/feishu/callback", consumes=MediaType.APPLICATION_JSON_VALUE)
    public Map<String,String> feishu(@RequestHeader Map<String,String> headers, @RequestBody String body) throws Exception {
        String raw=body;
        JsonNode root=mapper.readTree(raw);
        if (root.has("encrypt")) {
            String encrypted = root.path("encrypt").asText("");
            try {
                root = mapper.readTree(decryptFeishu(encrypted));
            } catch (Exception ex) {
                log.warn("Feishu encrypted event decrypt failed: payloadLength={}, encryptKeyConfigured={}", encrypted.length(), !feishuEncryptKey.isBlank(), ex);
                throw ex;
            }
        }
        if (root.has("challenge")) {
            String token = root.path("token").asText("");
            requireConfigured(feishuToken, "Feishu verification token");
            if (!MessageDigest.isEqual(feishuToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) throw new SecurityException("invalid feishu verification token");
            return Map.of("challenge",root.path("challenge").asText());
        }
        if (!verifyFeishu(headers, raw)) throw new SecurityException("invalid feishu signature");
        JsonNode event=root.path("event"); JsonNode message=event.path("message");
        String text=message.path("content").asText("");
        try { text=mapper.readTree(text).path("text").asText(text); } catch(Exception ignored) { }
        String user=event.at("/sender/sender_id/open_id").asText("feishu-user");
        String externalId=message.path("message_id").asText(root.path("event_id").asText(UUID.randomUUID().toString()));
        String conversation=message.path("chat_id").asText(user);
        long tenantId=operations.resolveTenant("feishu",root.at("/header/app_id").asText(""));
        Map<String,Object> result=operations.handleInbound(new ChannelOperationsService.InboundMessage(
                tenantId,"feishu",externalId,conversation,user,
                message.path("chat_type").asText("direct"),text,"",mapper.convertValue(root,Map.class)));
        return Map.of("status",String.valueOf(result.get("status")));
    }

    @GetMapping("/wechat/callback") public ResponseEntity<String> wechatVerify(@RequestParam Map<String,String> q) throws Exception {
        String echo=q.getOrDefault("echostr","");
        if (echo.isBlank() || !q.containsKey("msg_signature") || !q.containsKey("timestamp") || !q.containsKey("nonce")) return ResponseEntity.badRequest().body("");
        if(!verifyWechat(q.get("msg_signature"),q.get("timestamp"),q.get("nonce"),echo)) throw new SecurityException("invalid wechat signature");
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(decryptWechat(echo));
    }
    @PostMapping(value="/wechat/callback", consumes={MediaType.TEXT_XML_VALUE,MediaType.APPLICATION_XML_VALUE})
    public String wechat(@RequestParam Map<String,String> q,@RequestBody String body) throws Exception {
        requireKeys(q,"msg_signature","timestamp","nonce");
        if(!verifyWechat(q.get("msg_signature"),q.get("timestamp"),q.get("nonce"),xml(body,"Encrypt",""))) throw new SecurityException("invalid wechat signature");
        String payload=xml(body,"Encrypt",""); String plain=payload.isBlank()?body:decryptWechat(payload);
        String user=xml(plain,"FromUserName","wechat-user"); String text=xml(plain,"Content","");
        String externalId=xml(plain,"MsgId",xml(plain,"CreateTime",UUID.randomUUID().toString())+":"+user);
        String accountId=xml(plain,"ToUserName","");
        String conversation=accountId+":"+user;
        long tenantId=operations.resolveTenant("wechat",accountId);
        operations.handleInbound(new ChannelOperationsService.InboundMessage(tenantId,"wechat",
                externalId,conversation,user,"direct",text,"",Map.of("xml",plain))); return "success";
    }

    @PostMapping(value="/dingtalk/callback", consumes=MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String,String>> dingtalk(@RequestHeader Map<String,String> h,@RequestBody String body) throws Exception {
        requireKeys(h,"timestamp","sign");
        if(!verifyDing(h.get("timestamp"),h.get("sign"))) throw new SecurityException("invalid dingtalk signature");
        JsonNode root=mapper.readTree(body); String user=root.path("senderStaffId").asText(root.path("senderId").asText("dingtalk-user"));
        String text=root.at("/text/content").asText(root.path("content").asText(""));
        String externalId=root.path("msgId").asText(root.path("msgid").asText(UUID.randomUUID().toString()));
        String conversation=root.path("conversationId").asText(user);
        long tenantId=operations.resolveTenant("dingtalk",root.path("robotCode").asText(""));
        Map<String,Object> result=operations.handleInbound(new ChannelOperationsService.InboundMessage(
                tenantId,"dingtalk",externalId,conversation,user,
                root.path("conversationType").asText("direct"),text,root.path("sessionWebhook").asText(""),mapper.convertValue(root,Map.class)));
        return ApiResponse.ok(Map.of("status",String.valueOf(result.get("status"))));
    }
    private boolean verifyFeishu(Map<String,String> h,String body) throws Exception { requireConfigured(feishuEncryptKey,"Feishu encrypt key"); requireKeys(h,"x-lark-request-timestamp","x-lark-request-nonce","x-lark-signature"); if(!fresh(h.get("x-lark-request-timestamp"),1)) return false; String s=h.get("x-lark-request-timestamp")+h.get("x-lark-request-nonce")+feishuEncryptKey+body; return MessageDigest.isEqual(hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.UTF_8),h.get("x-lark-signature").getBytes(StandardCharsets.UTF_8)); }
    private boolean verifyDing(String ts,String sign) throws Exception { requireConfigured(dingSecret,"DingTalk signing secret"); if(!fresh(ts,1000)) return false; String v=Base64.getEncoder().encodeToString(hmac(dingSecret,(ts+"\n"+dingSecret).getBytes(StandardCharsets.UTF_8),"HmacSHA256")); return MessageDigest.isEqual(v.getBytes(StandardCharsets.UTF_8),sign.getBytes(StandardCharsets.UTF_8)); }
    private boolean verifyWechat(String sig,String ts,String nonce,String echo) throws Exception { requireConfigured(wechatToken,"WeCom token"); if(!fresh(ts,1)) return false; String[] a={wechatToken,ts,nonce,echo}; Arrays.sort(a); return sha1(String.join("",a)).equalsIgnoreCase(sig); }
    private String decryptFeishu(String s) throws Exception {
        byte[] key=MessageDigest.getInstance("SHA-256").digest(feishuEncryptKey.getBytes(StandardCharsets.UTF_8));
        byte[] p=decryptBytes(s,key,Arrays.copyOf(key,16));
        if (p.length < 20) throw new SecurityException("invalid feishu encrypted payload");
        // Feishu encrypted events use a 16-byte random prefix followed by JSON.
        // Some SDKs emit the same prefix plus a 4-byte length field; accept both.
        if (p[16] == '{' || p[16] == '[') return new String(p,16,p.length-16,StandardCharsets.UTF_8);
        int n=((p[16]&255)<<24)|((p[17]&255)<<16)|((p[18]&255)<<8)|(p[19]&255);
        if (n < 0 || 20+n > p.length) throw new SecurityException("invalid feishu payload length");
        return new String(p,20,n,StandardCharsets.UTF_8);
    }
    private String decryptWechat(String s) throws Exception {
        byte[] key=Base64.getDecoder().decode(wechatAesKey + "=".repeat((4 - wechatAesKey.length() % 4) % 4));
        Cipher c=Cipher.getInstance("AES/CBC/NoPadding"); c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(Arrays.copyOf(key,16)));
        byte[] p=c.doFinal(Base64.getDecoder().decode(s));
        if (p.length < 20) throw new SecurityException("invalid wechat encrypted payload");
        int n=((p[16]&255)<<24)|((p[17]&255)<<16)|((p[18]&255)<<8)|(p[19]&255);
        if (n < 0 || 20+n > p.length) throw new SecurityException("invalid wechat payload length");
        String corp = new String(p,20+n,p.length-20-n,StandardCharsets.UTF_8).replaceAll("\\u0000+$", "");
        if (!wechatCorpId.isBlank() && !corp.isBlank() && !wechatCorpId.equals(corp)) throw new SecurityException("invalid wechat corp id");
        return new String(p,20,n,StandardCharsets.UTF_8);
    }
    private String decrypt(String s,byte[] key,byte[] iv,int skip) throws Exception { Cipher c=Cipher.getInstance("AES/CBC/PKCS5Padding"); c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(iv)); byte[] p=c.doFinal(Base64.getDecoder().decode(s)); return new String(p,skip,p.length-skip,StandardCharsets.UTF_8); }
    private byte[] decryptBytes(String s,byte[] key,byte[] iv) throws Exception { Cipher c=Cipher.getInstance("AES/CBC/PKCS5Padding"); c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(iv)); return c.doFinal(Base64.getDecoder().decode(s)); }
    private static byte[] hmac(String secret,byte[] data,String alg)throws Exception{Mac m=Mac.getInstance(alg);m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),alg));return m.doFinal(data);}
    private static String sha1(String s)throws Exception{return hex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x));return s.toString();}
    private String xml(String body,String tag,String fallback)throws Exception{DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);f.setXIncludeAware(false);f.setExpandEntityReferences(false);Document d=f.newDocumentBuilder().parse(new InputSource(new StringReader(body)));var n=d.getElementsByTagName(tag);return n.getLength()==0?fallback:n.item(0).getTextContent();}
    private static boolean fresh(String value,long divisor){try{return Math.abs(Instant.now().getEpochSecond()-Long.parseLong(value)/divisor)<=300;}catch(Exception ignored){return false;}}
    private static void requireConfigured(String value,String name){if(value==null||value.isBlank())throw new SecurityException(name+" is not configured");}
    private static void requireKeys(Map<String,String> values,String... keys){for(String key:keys)if(values.getOrDefault(key,"").isBlank())throw new SecurityException("missing required signature field: "+key);}
}
