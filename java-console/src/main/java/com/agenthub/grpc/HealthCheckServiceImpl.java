package com.agenthub.grpc;

import com.agenthub.grpc.stub.HealthCheckGrpc;
import com.agenthub.grpc.stub.PingRequest;
import com.agenthub.grpc.stub.PingResponse;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class HealthCheckServiceImpl extends HealthCheckGrpc.HealthCheckImplBase {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckServiceImpl.class);

    @Override
    public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
        log.debug("收到来自 {} 的 Ping 请求", request.getSender());

        PingResponse response = PingResponse.newBuilder()
                .setResponder("Java-Console")
                .setTimestamp(System.currentTimeMillis())
                .setStatus("UP")
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();

        log.debug("Ping 响应已发送");
    }
}
