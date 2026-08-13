package com.agenthub.channel;

import com.agenthub.common.response.ApiResponse;
import com.agenthub.grpc.PythonAgentClient;
import com.agenthub.grpc.stub.ExecutionRequest;
import com.agenthub.grpc.stub.ExecutionResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
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
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/channel")
public class ChannelCallbackController {
    private static final Logger log = LoggerFactory.getLogger(ChannelCallbackController.class);
    private final ObjectMapper mapper;
    private final PythonAgentClient runtime;
    private final String defaultAgentId;
    private final RestClient http = RestClient.create();
    private final String feishuToken, feishuEncryptKey, feishuAppId, feishuAppSecret;
    private final String wechatToken, wechatAesKey, wechatCorpId, wechatSecret;
    private final String dingSecret, dingWebhook;

    public ChannelCallbackController(ObjectMapper mapper, PythonAgentClient runtime,
            @Value("${agenthub.channel.default-agent-id:1}") String defaultAgentId,
            @Value("${FEISHU_VERIFICATION_TOKEN:}") String feishuToken,
            @Value("${FEISHU_ENCRYPT_KEY:}") String feishuEncryptKey,
            @Value("${FEISHU_APP_ID:}") String feishuAppId,
            @Value("${FEISHU_APP_SECRET:}") String feishuAppSecret,
            @Value("${WECHAT_TOKEN:}") String wechatToken,
            @Value("${WECHAT_ENCODING_AES_KEY:}") String wechatAesKey,
            @Value("${WECHAT_CORP_ID:}") String wechatCorpId,
            @Value("${WECHAT_APP_SECRET:}") String wechatSecret,
            @Value("${DINGTALK_SIGN_SECRET:}") String dingSecret,
            @Value("${DINGTALK_WEBHOOK_URL:}") String dingWebhook) {
        this.mapper=mapper; this.runtime=runtime; this.defaultAgentId=defaultAgentId;
        this.feishuToken=feishuToken; this.feishuEncryptKey=feishuEncryptKey; this.feishuAppId=feishuAppId; this.feishuAppSecret=feishuAppSecret;
        this.wechatToken=wechatToken; this.wechatAesKey=wechatAesKey; this.wechatCorpId=wechatCorpId; this.wechatSecret=wechatSecret;
        this.dingSecret=dingSecret; this.dingWebhook=dingWebhook;
    }

    @GetMapping("/feishu/callback") public ResponseEntity<Map<String,String>> feishuVerify(@RequestParam(required=false) String challenge) { return ResponseEntity.ok(Map.of("challenge", challenge==null?"":challenge)); }
    @PostMapping(value="/feishu/callback", consumes=MediaType.APPLICATION_JSON_VALUE)
    public Map<String,String> feishu(@RequestHeader Map<String,String> headers, @RequestBody String body) throws Exception {
        String raw=body;
        if (headers.containsKey("x-lark-signature") && !verifyFeishu(headers, raw)) throw new SecurityException("invalid feishu signature");
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
            if (!feishuToken.isBlank() && !MessageDigest.isEqual(feishuToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) throw new SecurityException("invalid feishu verification token");
            return Map.of("challenge",root.path("challenge").asText());
        }
        String text=root.at("/event/message/content").asText(""); String user=root.at("/event/sender/sender_id/open_id").asText("feishu-user");
        String reply=dispatch("feishu",user,text); if(!reply.isBlank()) sendFeishu(user,reply); return Map.of("status","accepted");
    }

    @GetMapping("/wechat/callback") public ResponseEntity<String> wechatVerify(@RequestParam Map<String,String> q) throws Exception {
        String echo=q.getOrDefault("echostr","");
        if (echo.isBlank() || !q.containsKey("msg_signature") || !q.containsKey("timestamp") || !q.containsKey("nonce")) return ResponseEntity.badRequest().body("");
        if(!verifyWechat(q.get("msg_signature"),q.get("timestamp"),q.get("nonce"),echo)) throw new SecurityException("invalid wechat signature");
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(decryptWechat(echo));
    }
    @PostMapping(value="/wechat/callback", consumes={MediaType.TEXT_XML_VALUE,MediaType.APPLICATION_XML_VALUE})
    public String wechat(@RequestParam Map<String,String> q,@RequestBody String body) throws Exception {
        if(q.containsKey("msg_signature") && !verifyWechat(q.get("msg_signature"),q.get("timestamp"),q.get("nonce"),xml(body,"Encrypt",""))) throw new SecurityException("invalid wechat signature");
        String payload=xml(body,"Encrypt",""); String plain=payload.isBlank()?body:decryptWechat(payload); String user=xml(plain,"FromUserName","wechat-user"); String text=xml(plain,"Content",""); String reply=dispatch("wechat",user,text); if(!reply.isBlank()) sendWechat(user,reply); return "success";
    }

    @PostMapping(value="/dingtalk/callback", consumes=MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String,String>> dingtalk(@RequestHeader Map<String,String> h,@RequestBody String body) throws Exception {
        if(h.containsKey("timestamp") && h.containsKey("sign") && !verifyDing(h.get("timestamp"),h.get("sign"))) throw new SecurityException("invalid dingtalk signature");
        JsonNode root=mapper.readTree(body); String user=root.path("senderStaffId").asText(root.path("senderId").asText("dingtalk-user")); String text=root.at("/text/content").asText(root.path("content").asText("")); String reply=dispatch("dingtalk",user,text); if(!reply.isBlank()) sendDing(reply); return ApiResponse.ok(Map.of("status","accepted"));
    }

    private String dispatch(String channel,String user,String message) { if(message==null||message.isBlank()) return ""; AtomicReference<String> out=new AtomicReference<>(""); ExecutionRequest req=ExecutionRequest.newBuilder().setSessionId(channel+"-"+user+"-"+UUID.randomUUID()).setAgentId(defaultAgentId).setUserId(user).setTenantId("0").setMessage(message).setChannel(channel).build(); runtime.executeAgent(req,r->{if(r.getType()==ExecutionResponse.Type.TEXT) out.set(out.get()+r.getContent());},e->{},()->{}); return out.get(); }
    private boolean verifyFeishu(Map<String,String> h,String body) throws Exception { String s=h.get("x-lark-request-timestamp")+h.get("x-lark-request-nonce")+feishuEncryptKey+body; return MessageDigest.isEqual(hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).getBytes(),h.get("x-lark-signature").getBytes()); }
    private boolean verifyDing(String ts,String sign) throws Exception { String v=Base64.getEncoder().encodeToString(hmac(dingSecret,(ts+"\n"+dingSecret).getBytes(StandardCharsets.UTF_8),"HmacSHA256")); return MessageDigest.isEqual(v.getBytes(),sign.getBytes()); }
    private boolean verifyWechat(String sig,String ts,String nonce,String echo) throws Exception { String[] a={wechatToken,ts,nonce,echo}; Arrays.sort(a); return sha1(String.join("",a)).equalsIgnoreCase(sig); }
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
    private void sendDing(String text){if(!dingWebhook.isBlank()) http.post().uri(dingWebhook).contentType(MediaType.APPLICATION_JSON).body(Map.of("msgtype","text","text",Map.of("content",text))).retrieve().toBodilessEntity();}
    private void sendFeishu(String user,String text){
        if(feishuAppId.isBlank()||feishuAppSecret.isBlank()) return;
        try {
            Map<?,?> token=http.post().uri("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal").contentType(MediaType.APPLICATION_JSON).body(Map.of("app_id",feishuAppId,"app_secret",feishuAppSecret)).retrieve().body(Map.class);
            String access=String.valueOf(token.get("tenant_access_token"));
            http.post().uri("https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=open_id").header("Authorization","Bearer "+access).contentType(MediaType.APPLICATION_JSON).body(Map.of("receive_id",user,"msg_type","text","content",mapper.writeValueAsString(Map.of("text",text)))).retrieve().toBodilessEntity();
        } catch(Exception ignored) { }
    }
    private void sendWechat(String user,String text){
        if(wechatCorpId.isBlank()||wechatSecret.isBlank()) return;
        try {
            Map<?,?> token=http.get().uri("https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid="+wechatCorpId+"&corpsecret="+wechatSecret).retrieve().body(Map.class);
            String access=String.valueOf(token.get("access_token"));
            http.post().uri("https://qyapi.weixin.qq.com/cgi-bin/message/send?access_token="+access).contentType(MediaType.APPLICATION_JSON).body(Map.of("touser",user,"msgtype","text","agentid",Integer.parseInt(System.getenv().getOrDefault("WECHAT_AGENT_ID","0")),"text",Map.of("content",text))).retrieve().toBodilessEntity();
        } catch(Exception ignored) { }
    }
    private static byte[] hmac(String secret,byte[] data,String alg)throws Exception{Mac m=Mac.getInstance(alg);m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),alg));return m.doFinal(data);}
    private static String sha1(String s)throws Exception{return hex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x));return s.toString();}
    private String xml(String body,String tag,String fallback)throws Exception{Document d=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(body))); var n=d.getElementsByTagName(tag); return n.getLength()==0?fallback:n.item(0).getTextContent();}
}
