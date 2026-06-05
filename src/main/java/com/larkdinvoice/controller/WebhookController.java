package com.larkdinvoice.controller;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkdinvoice.config.AppConfig;
import com.larkdinvoice.handler.ApprovalEventHandler;
import com.larkdinvoice.model.LarkEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final AppConfig appConfig;
    private final ApprovalEventHandler approvalEventHandler;
    private final ObjectMapper objectMapper;

    @PostMapping("/lark")
    public ResponseEntity<?> receive(
            @RequestHeader(value = "X-Lark-Signature", required = false) String signature,
            @RequestHeader(value = "X-Lark-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Lark-Request-Nonce", required = false) String nonce,
            @RequestBody String rawBody) {

        LarkEvent event;
        try {
            event = objectMapper.readValue(rawBody, LarkEvent.class);
        } catch (Exception e) {
            log.warn("Failed to parse Lark event body", e);
            return ResponseEntity.badRequest().build();
        }

        if ("url_verification".equals(event.getType())) {
            return ResponseEntity.ok(Map.of("challenge", event.getChallenge()));
        }

        if (!verifySignature(timestamp, nonce, rawBody, signature)) {
            log.warn("Lark webhook signature verification failed");
            return ResponseEntity.status(403).build();
        }

        approvalEventHandler.handle(event);
        return ResponseEntity.ok().build();
    }

    private boolean verifySignature(String timestamp, String nonce, String body, String signature) {
        if (timestamp == null || nonce == null || signature == null) {
            return false;
        }
        String content = timestamp + nonce + appConfig.getEncryptKey() + body;
        String expected = DigestUtil.sha256Hex(content);
        return expected.equals(signature);
    }
}
