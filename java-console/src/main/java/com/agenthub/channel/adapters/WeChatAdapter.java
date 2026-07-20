package com.agenthub.channel.adapters;

import com.agenthub.channel.ChannelAdapter;
import org.springframework.stereotype.Component;
import java.util.Map;

/**
 * 企业微信适配器（桩实现）
 * 正式对接需要在企业微信后台配置回调 URL 和 Token
 */
@Component
public class WeChatAdapter implements ChannelAdapter {

    @Override
    public String getType() { return "wechat"; }

    @Override
    public String onMessage(String userId, String message, Map<String, String> context) {
        // TODO: 解析企微 XML 消息体
        // 1. 验证签名 (msg_signature, timestamp, nonce)
        // 2. 解密 XML → 提取文本
        // 3. 调用 Agent Runtime 获取回复
        // 4. 加密回复 → 返回 XML
        return message;
    }
}
