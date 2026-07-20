package com.agenthub.channel.adapters;

import com.agenthub.channel.ChannelAdapter;
import org.springframework.stereotype.Component;
import java.util.Map;

/**
 * 飞书适配器（桩实现）
 * 正式对接需要在飞书开放平台创建应用并配置事件订阅
 */
@Component
public class FeishuAdapter implements ChannelAdapter {

    @Override
    public String getType() { return "feishu"; }

    @Override
    public String onMessage(String userId, String message, Map<String, String> context) {
        // TODO: 对接飞书机器人
        // 1. 获取 tenant_access_token
        // 2. 解析事件 JSON → 提取消息
        // 3. 调用 Agent Runtime
        // 4. 通过飞书 API 发送回复消息
        return message;
    }
}
