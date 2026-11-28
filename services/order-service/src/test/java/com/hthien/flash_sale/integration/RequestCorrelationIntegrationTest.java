package com.hthien.flash_sale.integration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@TestPropertySource(properties = "app.instance-id=correlation-test")
@ExtendWith(OutputCaptureExtension.class)
class RequestCorrelationIntegrationTest extends BaseIntegrationTest {

    @Autowired private MockMvc mvc;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void incomingId_isReturnedInErrorAndLogs_thenMdcIsCleared(CapturedOutput output) throws Exception {
        mvc.perform(get("/api/v1/products/999999").header("X-Request-Id", "purchase-123"))
            .andExpect(status().isNotFound())
            .andExpect(header().string("X-Request-Id", "purchase-123"))
            .andExpect(header().string("X-Instance-Id", "correlation-test"))
            .andExpect(jsonPath("$.requestId").value("purchase-123"))
            .andExpect(jsonPath("$.error").value("PRODUCT_NOT_FOUND"));
        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("instanceId")).isNull();
        assertThat(output).contains("instanceId=correlation-test", "requestId=purchase-123",
            "HTTP GET /api/v1/products/999999 status=404");
    }

    @Test
    void generatedIds_areDifferentAndPreviousContextIsRestored() throws Exception {
        MDC.put("requestId", "outer-context");
        MDC.put("unrelated", "preserved");
        String first = mvc.perform(get("/actuator/health"))
            .andExpect(status().isOk()).andReturn().getResponse().getHeader("X-Request-Id");
        String second = mvc.perform(get("/actuator/health"))
            .andExpect(status().isOk()).andReturn().getResponse().getHeader("X-Request-Id");
        assertThat(first).isNotBlank().isNotEqualTo(second);
        assertThat(java.util.UUID.fromString(first)).isNotNull();
        assertThat(MDC.get("requestId")).isEqualTo("outer-context");
        assertThat(MDC.get("unrelated")).isEqualTo("preserved");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "bad id", "bad\nid"})
    void unsafeIds_areReplaced(String incoming) throws Exception {
        String actual = mvc.perform(get("/api/v1/products/999999").header("X-Request-Id", incoming))
            .andExpect(status().isNotFound())
            .andReturn().getResponse().getHeader("X-Request-Id");
        assertThat(actual).isNotBlank().isNotEqualTo(incoming);
        assertThat(java.util.UUID.fromString(actual)).isNotNull();
    }
}
