package com.hthien.flash_sale.exception;

import java.time.Instant;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ErrorResponse {
    private Instant timestamp;
    private int status;
    private String error;
    private String message;
    private String path;

    public static ErrorResponse of(int status, String error, String message, String path) {
        return ErrorResponse.builder()
        .timestamp(Instant.now())
        .status(status)
        .error(error)
        .message(message)
        .path(path)
        .build();
    }
}
