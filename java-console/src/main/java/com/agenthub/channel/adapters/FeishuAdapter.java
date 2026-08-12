package com.agenthub.channel.adapters;
import com.agenthub.channel.ChannelAdapter;
import com.agenthub.channel.ChannelSecurity;
import org.springframework.stereotype.Component;
import java.util.Map;
@Component public class FeishuAdapter implements ChannelAdapter {
 public String getType(){return "feishu";}
 public String onMessage(String userId,String message,Map<String,String> context){if(!ChannelSecurity.authenticated(context,message,context.get("appSecret"))) throw new SecurityException("Unauthenticated Feishu event"); return message;}
}
