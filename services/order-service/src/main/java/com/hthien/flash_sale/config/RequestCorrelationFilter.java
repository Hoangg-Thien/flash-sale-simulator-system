package com.hthien.flash_sale.config;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class RequestCorrelationFilter extends OncePerRequestFilter {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private final String instanceId;

    public RequestCorrelationFilter(@Value("${app.instance-id:local}") String instanceId) {
        this.instanceId = instanceId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader("X-Request-Id");
        String requestId = incoming != null && SAFE_ID.matcher(incoming).matches()
            ? incoming : UUID.randomUUID().toString();
        Map<String, String> previous = MDC.getCopyOfContextMap();
        MDC.put("requestId", requestId);
        MDC.put("instanceId", instanceId);
        response.setHeader("X-Request-Id", requestId);
        response.setHeader("X-Instance-Id", instanceId);
        try {
            chain.doFilter(request, response);
        } finally {
            log.info("HTTP {} {} status={}", request.getMethod(), request.getRequestURI(), response.getStatus());
            if (previous == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        }
    }
}
