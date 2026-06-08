package com.larkdinvoice.controller;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkdinvoice.config.AppConfig;
import com.larkdinvoice.handler.ApprovalEventHandler;
import com.larkdinvoice.model.LarkEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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

        // 飞书加密模式：请求体为 {"encrypt": "..."}，需要先解密
        String bodyToProcess = rawBody;
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            if (root.has("encrypt")) {
                String encrypted = root.get("encrypt").asText();
                bodyToProcess = decrypt(encrypted, appConfig.getEncryptKey());
                log.info("飞书加密消息解密成功，内容：{}", bodyToProcess);
            }
        } catch (Exception e) {
            log.warn("飞书消息解密失败：{}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }

        LarkEvent event;
        try {
            event = objectMapper.readValue(bodyToProcess, LarkEvent.class);
        } catch (Exception e) {
            log.warn("Failed to parse Lark event body: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }

        // URL 验证握手（飞书验证 Webhook 地址时发送）
        if ("url_verification".equals(event.getType())) {
            log.info("收到飞书 URL 验证请求，challenge：{}", event.getChallenge());
            return ResponseEntity.ok(Map.of("challenge", event.getChallenge()));
        }

        // 正常事件，验签（基于原始 rawBody）
        if (!verifySignature(timestamp, nonce, rawBody, signature)) {
            log.warn("Lark webhook signature verification failed");
            return ResponseEntity.status(403).build();
        }

        approvalEventHandler.handle(event);
        return ResponseEntity.ok().build();
    }

    /**
     * 飞书 AES-256-CBC 解密
     * key = SHA-256(encryptKey) 前32字节
     * base64解码后：前16字节为 IV，剩余为密文
     */
    private String decrypt(String encryptedStr, String encryptKey) throws Exception {
        byte[] keyBytes = DigestUtil.sha256(encryptKey.getBytes(StandardCharsets.UTF_8));
        byte[] decoded = Base64.getDecoder().decode(encryptedStr);

        byte[] iv = new byte[16];
        byte[] cipherText = new byte[decoded.length - 16];
        System.arraycopy(decoded, 0, iv, 0, 16);
        System.arraycopy(decoded, 16, cipherText, 0, cipherText.length);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(keyBytes, "AES"),
                new IvParameterSpec(iv));
        byte[] result = cipher.doFinal(cipherText);
        return new String(result, StandardCharsets.UTF_8);
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
