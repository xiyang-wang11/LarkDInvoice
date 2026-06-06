package com.larkdinvoice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkdinvoice.config.AppConfig;
import com.larkdinvoice.model.InvoiceRequest;
import com.larkdinvoice.model.InvoiceResult;
import com.larkdinvoice.service.KingdeeAuthService;
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
import static org.mockito.Mockito.*;

class KingdeeInvoiceClientTest {

    private MockWebServer mockWebServer;
    private KingdeeInvoiceClientImpl client;
    private KingdeeAuthService authService;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        AppConfig.KingdeeConfig config = new AppConfig.KingdeeConfig();
        config.setOpenInvoiceUrl(mockWebServer.url("/kapi/app/sim/openApi").toString());
        config.setBusinessSystemCode("TEST");
        config.setAppId("test-app-id");
        config.setAppSecret("test-app-secret");
        config.setAccountId("test-account-id");
        config.setUser("13800000000");

        authService = mock(KingdeeAuthService.class);
        when(authService.getAccessToken()).thenReturn("test-access-token");

        client = new KingdeeInvoiceClientImpl(config, authService, objectMapper, 2);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void shouldReturnSuccessWhenApiReturnsInvoiceNum() throws Exception {
        // 金蝶真实响应：status=true + invoiceNum
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"status\":true,\"returnCode\":\"0\",\"returnMsg\":\"success\",\"invoiceNum\":\"24865868013476259840\"}")
                .addHeader("Content-Type", "application/json"));

        InvoiceResult result = client.createInvoice(buildRequest());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getInvoiceNo()).isEqualTo("24865868013476259840");
    }

    @Test
    void shouldReturnFailureWhenApiReturnsFailed() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"status\":false,\"returnCode\":\"9999\",\"message\":\"税号无效\"}")
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
        assertThat(result.getErrorMsg()).isNotBlank();
    }

    @Test
    void shouldIncludeAccessTokenHeaderInRequest() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"status\":true,\"invoiceNum\":\"INV-001\"}")
                .addHeader("Content-Type", "application/json"));

        client.createInvoice(buildRequest());

        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getHeader("accessToken")).isEqualTo("test-access-token");
    }

    @Test
    void shouldRefreshTokenAndRetryWhen401() throws Exception {
        // 第一次返回 401，第二次成功
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"returnCode\":\"401\",\"message\":\"token已过期\"}")
                .addHeader("Content-Type", "application/json"));
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"status\":true,\"invoiceNum\":\"INV-RETRY-001\"}")
                .addHeader("Content-Type", "application/json"));

        InvoiceResult result = client.createInvoice(buildRequest());

        assertThat(result.isSuccess()).isTrue();
        verify(authService, times(1)).refreshTokens();
    }

    private InvoiceRequest buildRequest() {
        return InvoiceRequest.builder()
                .requestNo("instance-001")
                .buyerName("测试公司")
                .buyerTaxNo("91110000123456789X")
                .buyerAddressPhone("北京市 010-12345678")
                .buyerBankAccount("工商银行 6222001234567890")
                .invoiceType("10xdp")
                .totalAmount(new BigDecimal("10000.00"))
                .items(Collections.emptyList())
                .build();
    }
}
