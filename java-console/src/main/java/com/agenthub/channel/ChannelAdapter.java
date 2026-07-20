package com.agenthub.channel;

import java.util.Map;

/**
 * 渠道适配器接口 — 统一消息收发
 * 所有外部渠道（企微/钉钉/飞书/API/网页）实现此接口
 */
public interface ChannelAdapter {

    /** 渠道类型标识 */
    String getType();

    /** 接收来自渠道的消息，返回回复内容 */
    String onMessage(String userId, String message, Map<String, String> context);
}
