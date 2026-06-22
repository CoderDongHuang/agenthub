package com.agenthub.grpc;

import com.agenthub.grpc.stub.HealthCheckGrpc;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
public class GrpcServerConfig {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerConfig.class);

    private final int port;
    private final HealthCheckServiceImpl healthCheckService;
    private Server server;

    public GrpcServerConfig(
            @Value("${grpc.server.port:9090}") int port,
            HealthCheckServiceImpl healthCheckService) {
        this.port = port;
        this.healthCheckService = healthCheckService;
    }

    @PostConstruct
    public void start() throws IOException {
        server = ServerBuilder.forPort(port)
                .addService(healthCheckService)
                .build()
                .start();

        log.info("gRPC Server 启动成功，监听端口: {}", port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("正在关闭 gRPC Server...");
            try {
                GrpcServerConfig.this.stop();
            } catch (InterruptedException e) {
                log.error("关闭 gRPC Server 被中断", e);
                Thread.currentThread().interrupt();
            }
        }));
    }

    @PreDestroy
    public void stop() throws InterruptedException {
        if (server != null) {
            server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            log.info("gRPC Server 已关闭");
        }
    }
}
