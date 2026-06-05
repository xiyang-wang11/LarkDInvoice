package com.larkdinvoice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkdinvoice.config.AppConfig;
import com.larkdinvoice.model.InvoiceRequest;
import com.larkdinvoice.model.InvoiceResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class KingdeeInvoiceClientTest {

    private MockWebServer mockWebServer;
    private KingdeeInvoiceClientImpl client;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        AppConfig.KingdeeConfig config = new AppConfig.KingdeeConfig();
        config.setApiUrl(mockWebServer.url("/").toString());
        config.setAppKey("test-app-key");
        config.setAppSecret("test-app-secret");

        client = new KingdeeInvoiceClientImpl(config, objectMapper);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void shouldReturnSuccessWhenApiReturnsInvoiceNo() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"success\":true,\"invoiceNo\":\"INV-2026-001\"}")
                .addHeader("Content-Type", "application/json"));

        InvoiceResult result = client.createInvoice(buildRequest());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getInvoiceNo()).isEqualTo("INV-2026-001");
    }

    @Test
    void shouldReturnFailureWhenApiReturnsError() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"success\":false,\"errorCode\":\"E001\",\"errorMsg\":\"税号无效\"}")
                .addHeader("Content-Type", "application/json"));

        InvoiceResult result = client.createInvoice(buildRequest());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("税号无效");
    }

    @Test
    void shouldReturnFailureWhenApiTimesOut() {
        mockWebServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        InvoiceResult result = client.createInvoice(buildRequest());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("timeout");
    }

    @Test
    void shouldIncludeAuthHeaderInRequest() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"success\":true,\"invoiceNo\":\"INV-001\"}")
                .addHeader("Content-Type", "application/json"));

        client.createInvoice(buildRequest());

        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getHeader("X-App-Key")).isEqualTo("test-app-key");
        assertThat(request.getHeader("X-Signature")).isNotBlank();
    }

    private InvoiceRequest buildRequest() {
        return InvoiceRequest.builder()
                .requestNo("instance-001")
                .buyerName("测试公司")
                .buyerTaxNo("91110000123456789X")
                .buyerAddressPhone("北京市 010-12345678")
                .buyerBankAccount("工商银行 6222001234567890")
                .invoiceType("增值税专用发票")
                .totalAmount(new BigDecimal("10000.00"))
                .items(Collections.emptyList())
                .build();
    }
}
