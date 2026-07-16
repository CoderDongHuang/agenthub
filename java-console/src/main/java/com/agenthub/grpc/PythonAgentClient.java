package com.agenthub.grpc;

import com.agenthub.grpc.stub.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Component
public class PythonAgentClient {

    private static final Logger log = LoggerFactory.getLogger(PythonAgentClient.class);

    private final String pythonGrpcHost;
    private final int pythonGrpcPort;
    private ManagedChannel channel;
    private AgentExecutionGrpc.AgentExecutionStub asyncStub;

    public PythonAgentClient(
            @Value("${grpc.python.host:localhost}") String pythonGrpcHost,
            @Value("${grpc.python.port:9091}") int pythonGrpcPort) {
        this.pythonGrpcHost = pythonGrpcHost;
        this.pythonGrpcPort = pythonGrpcPort;
    }

    @PostConstruct
    public void init() {
        channel = ManagedChannelBuilder.forAddress(pythonGrpcHost, pythonGrpcPort)
                .usePlaintext()
                .build();
        asyncStub = AgentExecutionGrpc.newStub(channel);
        log.info("gRPC 客户端已连接 Python Engine: {}:{}", pythonGrpcHost, pythonGrpcPort);
    }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        if (channel != null) {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * 执行 Agent 对话（双向流），通过回调接收流式结果
     */
    public void executeAgent(ExecutionRequest request,
                             Consumer<ExecutionResponse> onResponse,
                             Consumer<Throwable> onError,
                             Runnable onCompleted) {
        CountDownLatch latch = new CountDownLatch(1);

        StreamObserver<ExecutionRequest> requestObserver = asyncStub.streamExecute(
                new StreamObserver<ExecutionResponse>() {
                    @Override
                    public void onNext(ExecutionResponse response) {
                        onResponse.accept(response);
                    }

                    @Override
                    public void onError(Throwable t) {
                        log.error("Agent 执行异常", t);
                        onError.accept(t);
                        latch.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        log.debug("Agent 执行完成");
                        onCompleted.run();
                        latch.countDown();
                    }
                });

        // 发送请求
        requestObserver.onNext(request);
        requestObserver.onCompleted();

        // 等待完成（最多 5 分钟）
        try {
            latch.await(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
