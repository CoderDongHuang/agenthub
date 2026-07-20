package com.agenthub.channel.adapters;

import com.agenthub.channel.ChannelAdapter;
import org.springframework.stereotype.Component;
import java.util.Map;

/**
 * 钉钉适配器（桩实现）
 * 正式对接需要在钉钉开放平台创建机器人应用
 */
@Component
public class DingTalkAdapter implements ChannelAdapter {

    @Override
    public String getType() { return "dingtalk"; }

    @Override
    public String onMessage(String userId, String message, Map<String, String> context) {
        // TODO: 对接钉钉机器人 Webhook
        // 1. 验签 (timestamp + appSecret → HMAC-SHA256)
        // 2. 解析 JSON → 提取文本
        // 3. 调用 Agent Runtime
        // 4. 通过 Webhook 回复消息
        return message;
    }
}
