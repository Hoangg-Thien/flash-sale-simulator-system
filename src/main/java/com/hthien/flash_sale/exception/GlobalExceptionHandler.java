package com.hthien.flash_sale.exception;

import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.hthien.flash_sale.dto.response.OrderResponse;

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

    // Hết hàng → 409 Conflict
    @ExceptionHandler(InsufficientStockException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleInsufficientStock(InsufficientStockException ex, HttpServletRequest request) {
        return ErrorResponse.of(409, "INSUFFICIENT_STOCK", ex.getMessage(), request.getRequestURI());
    }

    /* 
    Idempotency duplicate: trả lại response cũ với HTTP 200
    Tại sao 200 thay vì 409?
    RFC 9110 / Stripe spec: idempotent duplicate = "đã xử lý thành công",
    trả lại kết quả cũ. 409 chỉ dùng khi có conflict thật sự về state.
    */
    @ExceptionHandler(DuplicateOrderException.class)
    @ResponseStatus(HttpStatus.OK)
    public OrderResponse handleDuplicateOrder(DuplicateOrderException ex) {
        return OrderResponse.from(ex.getExistingOrder());
    }

    // lockMode không hợp lệ → 400 Bad Request
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

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleGeneral(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error: path={}", request.getRequestURI(), ex);
        return ErrorResponse.of(500, "INTERNAL_ERROR",
        "An unexpected error occurred", request.getRequestURI());
    }

}
