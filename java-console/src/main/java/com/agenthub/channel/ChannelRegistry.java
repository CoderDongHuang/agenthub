package com.agenthub.channel;

import org.springframework.stereotype.Component;
import java.util.*;

/**
 * 渠道注册中心 — 管理所有 ChannelAdapter 实现
 */
@Component
public class ChannelRegistry {

    private final Map<String, ChannelAdapter> adapters = new HashMap<>();

    public ChannelRegistry(List<ChannelAdapter> adapterList) {
        for (ChannelAdapter a : adapterList) {
            adapters.put(a.getType(), a);
        }
    }

    public ChannelAdapter get(String type) {
        return adapters.get(type);
    }

    public Set<String> listTypes() {
        return adapters.keySet();
    }

    public int count() {
        return adapters.size();
    }
}
