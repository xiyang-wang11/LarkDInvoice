package com.larkdinvoice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkdinvoice.config.AppConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class LarkNotifyServiceTest {

    private MockWebServer mockWebServer;
    private LarkNotifyServiceImpl service;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        AppConfig config = new AppConfig();
        config.setAppId("test-app-id");
        config.setAppSecret("test-app-secret");

        service = new LarkNotifyServiceImpl(config, objectMapper,
                mockWebServer.url("/").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void shouldSendSuccessMessageWithInvoiceNo() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"code\":0,\"tenant_access_token\":\"test-token\",\"expire\":7200}")
                .addHeader("Content-Type", "application/json"));
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"code\":0}")
                .addHeader("Content-Type", "application/json"));

        service.notifySuccess("ou_test123", "INV-2026-001", new BigDecimal("10000.00"));

        mockWebServer.takeRequest(); // token request
        RecordedRequest msgRequest = mockWebServer.takeRequest();
        String body = msgRequest.getBody().readUtf8();
        assertThat(body).contains("INV-2026-001");
        assertThat(body).contains("10000.00");
    }

    @Test
    void shouldSendFailureMessageWithErrorReason() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"code\":0,\"tenant_access_token\":\"test-token\",\"expire\":7200}")
                .addHeader("Content-Type", "application/json"));
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"code\":0}")
                .addHeader("Content-Type", "application/json"));

        service.notifyFailure("ou_test123", "税号格式不正确");

        mockWebServer.takeRequest();
        RecordedRequest msgRequest = mockWebServer.takeRequest();
        String body = msgRequest.getBody().readUtf8();
        assertThat(body).contains("税号格式不正确");
    }
}
