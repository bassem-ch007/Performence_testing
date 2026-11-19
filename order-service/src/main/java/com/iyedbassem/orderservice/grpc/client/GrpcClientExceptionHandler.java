package com.iyedbassem.orderservice.grpc.client;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates failures of downstream gRPC calls (user-service / product-service)
 * into explicit HTTP statuses instead of a generic 500.
 */
@Slf4j
@RestControllerAdvice
public class GrpcClientExceptionHandler {

    @ExceptionHandler(StatusRuntimeException.class)
    public ResponseEntity<String> handleGrpcFailure(StatusRuntimeException ex) {
        Status.Code code = ex.getStatus().getCode();
        HttpStatus httpStatus = switch (code) {
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case DEADLINE_EXCEEDED -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_GATEWAY;
        };
        log.warn("Downstream gRPC call failed: {} - {}", code, ex.getStatus().getDescription());
        return ResponseEntity.status(httpStatus).body("downstream gRPC call failed: " + code);
    }
}
