package com.larkdinvoice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkdinvoice.config.AppConfig;
import com.larkdinvoice.handler.ApprovalEventHandler;
import com.larkdinvoice.model.LarkEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WebhookController.class)
@ActiveProfiles("test")
class WebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ApprovalEventHandler approvalEventHandler;

    @MockBean
    private AppConfig appConfig;

    @Test
    void shouldReturnChallengeForUrlVerification() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "challenge", "test-challenge-value",
                "token", "test-token",
                "type", "url_verification"
        ));

        mockMvc.perform(post("/webhook/lark")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Lark-Signature", "dummy")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").value("test-challenge-value"));
    }

    @Test
    void shouldReturn403WhenSignatureInvalid() throws Exception {
        when(appConfig.getEncryptKey()).thenReturn("test-encrypt-key");
        String body = "{\"schema\":\"2.0\",\"header\":{\"event_type\":\"approval_instance\"}}";

        mockMvc.perform(post("/webhook/lark")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Lark-Signature", "invalid-signature")
                        .header("X-Lark-Request-Timestamp", "1234567890")
                        .header("X-Lark-Request-Nonce", "test-nonce")
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturn200AndDelegateToHandlerWhenSignatureValid() throws Exception {
        String encryptKey = "test-encrypt-key";
        when(appConfig.getEncryptKey()).thenReturn(encryptKey);

        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = "test-nonce";
        String body = objectMapper.writeValueAsString(Map.of(
                "schema", "2.0",
                "header", Map.of("event_type", "approval_instance")
        ));
        String signature = cn.hutool.crypto.digest.DigestUtil.sha256Hex(
                timestamp + nonce + encryptKey + body);

        mockMvc.perform(post("/webhook/lark")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Lark-Signature", signature)
                        .header("X-Lark-Request-Timestamp", timestamp)
                        .header("X-Lark-Request-Nonce", nonce)
                        .content(body))
                .andExpect(status().isOk());

        verify(approvalEventHandler, timeout(1000)).handle(any(LarkEvent.class));
    }
}
