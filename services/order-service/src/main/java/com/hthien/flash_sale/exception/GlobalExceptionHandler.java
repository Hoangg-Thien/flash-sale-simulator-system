package com.hthien.flash_sale.exception;

import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    
    @ExceptionHandler(ProductNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleProductNotFound(ProductNotFoundException ex, HttpServletRequest request){
        return ErrorResponse.of(404,"PRODUCT_NOT_FOUND", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
        .map(e -> e.getField() + ": " + e.getDefaultMessage())
        .collect(Collectors.joining(", "));
        return ErrorResponse.of(400, "VALIDATION_FAILED", message, request.getRequestURI());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleInvalidBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return ErrorResponse.of(400, "INVALID_BODY", "Malformed or invalid request body", request.getRequestURI());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return ErrorResponse.of(400, "INVALID_ARGUMENT", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(SimulationNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleSimulationNotFound(SimulationNotFoundException ex, HttpServletRequest request){
        return ErrorResponse.of(404, "SIMULATION_NOT_FOUND", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleStatus(
            ResponseStatusException ex, HttpServletRequest request) {
        int status = ex.getStatusCode().value();
        return ResponseEntity.status(status).body(
            ErrorResponse.of(status, ex.getStatusCode().toString(), ex.getReason(), request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleGeneral(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error: path={}", request.getRequestURI(), ex);
        return ErrorResponse.of(500, "INTERNAL_ERROR",
        "An unexpected error occurred", request.getRequestURI());
    }

}
