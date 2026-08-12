package com.agenthub.channel;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
public final class ChannelSecurity {
 private ChannelSecurity() {}
 public static boolean authenticated(Map<String,String> c,String payload,String secret){
  try { long ts=Long.parseLong(c.getOrDefault("timestamp","0")); if(Math.abs(Instant.now().getEpochSecond()-ts)>300||c.getOrDefault("eventId","").isBlank()||secret==null||secret.isBlank()) return false;
   Mac m=Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
   String expected=Base64.getEncoder().encodeToString(m.doFinal((c.get("timestamp")+"\n"+payload).getBytes(StandardCharsets.UTF_8)));
   return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),c.getOrDefault("signature","").getBytes(StandardCharsets.UTF_8));
  } catch(Exception e){ return false; }
 }
}
