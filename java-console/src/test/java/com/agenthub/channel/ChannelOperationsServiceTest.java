package com.agenthub.channel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChannelOperationsServiceTest {

    @Test
    void exponentialBackoffIsBounded() {
        assertEquals(5, ChannelOperationsService.backoffSeconds(1));
        assertEquals(10, ChannelOperationsService.backoffSeconds(2));
        assertEquals(20, ChannelOperationsService.backoffSeconds(3));
        assertEquals(40, ChannelOperationsService.backoffSeconds(4));
        assertEquals(3600, ChannelOperationsService.backoffSeconds(20));
    }

    @Test
    void mentionIsRemovedBeforeAgentExecution() {
        assertEquals("please handle this", ChannelOperationsService.stripMention("@ops please handle this"));
        assertEquals("请处理工单", ChannelOperationsService.stripMention("@客服 请处理工单"));
    }
}
