package com.hthien.inventory.integration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.hthien.inventory.model.Reservation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Test classpath only. Block serialization after the real controller returns its committed reservation. */
@ControllerAdvice
@ConditionalOnProperty(name = "test.commit-reply-barrier", havingValue = "true")
class CommitReplyBarrier implements ResponseBodyAdvice<Object> {
    public boolean supports(MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) {
        return method.getParameterType() == Reservation.class;
    }

    public Object beforeBodyWrite(Object body, MethodParameter method, MediaType type,
            Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
        String operation = method.getMethod().getName();
        Path gate = Path.of("/tmp/barrier-" + operation);
        if (Files.exists(gate) && body instanceof Reservation result) {
            try {
                Files.writeString(Path.of(gate + ".reached"), Long.toString(result.orderId()));
                long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
                while (Files.exists(gate) && System.nanoTime() < deadline) Thread.sleep(25);
                if (Files.exists(gate)) throw new IllegalStateException("Unreleased test barrier " + gate);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        return body;
    }
}
