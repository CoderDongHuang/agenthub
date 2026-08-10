package com.agenthub.platform.service;

import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;

@Component
public class WebhookUrlValidator {
    public URI validate(String rawUrl) {
        final URI uri;
        try {
            uri = URI.create(rawUrl).normalize();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Webhook URL is invalid");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Webhook URL must be an HTTPS URL without user information");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IllegalArgumentException("Webhook URL must use port 443");
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
            if (addresses.length == 0) throw new IllegalArgumentException("Webhook host cannot be resolved");
            for (InetAddress address : addresses) {
                if (isBlocked(address)) {
                    throw new IllegalArgumentException("Webhook host resolves to a non-public address");
                }
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Webhook host cannot be resolved");
        }
        return uri;
    }

    private boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first == 0 || first == 127 || first >= 224
                    || (first == 100 && second >= 64 && second <= 127);
        }
        if (address instanceof Inet6Address) {
            return (bytes[0] & 0xfe) == 0xfc;
        }
        return true;
    }
}
