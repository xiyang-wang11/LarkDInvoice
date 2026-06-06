package com.larkdinvoice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkdinvoice.config.AppConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class LarkNotifyServiceImpl implements LarkNotifyService {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final AppConfig appConfig;
    private final ObjectMapper objectMapper;
    private final String larkBaseUrl;
    private final OkHttpClient httpClient;

    private final AtomicReference<String> cachedToken = new AtomicReference<>();
    private final AtomicLong tokenExpireAt = new AtomicLong(0);

    public LarkNotifyServiceImpl(AppConfig appConfig, ObjectMapper objectMapper, String larkBaseUrl) {
        this.appConfig = appConfig;
        this.objectMapper = objectMapper;
        this.larkBaseUrl = larkBaseUrl;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    public LarkNotifyServiceImpl(AppConfig appConfig, ObjectMapper objectMapper) {
        this(appConfig, objectMapper, "https://open.feishu.cn");
    }

    @Override
    public void notifySuccess(String openId, String invoiceNo, BigDecimal amount) {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String text = String.format("开票成功\n发票号：%s\n金额：%s 元\n时间：%s",
                invoiceNo, amount.toPlainString(), time);
        sendMessage(openId, text);
    }

    @Override
    public void notifyFailure(String openId, String reason) {
        String text = String.format("开票失败\n原因：%s\n请联系管理员处理", reason);
        sendMessage(openId, text);
    }

    private void sendMessage(String openId, String text) {
        try {
            String token = getAccessToken();

            Map<String, Object> contentMap = new HashMap<>();
            contentMap.put("text", text);

            Map<String, Object> payload = new HashMap<>();
            payload.put("receive_id", openId);
            payload.put("msg_type", "text");
            payload.put("content", objectMapper.writeValueAsString(contentMap));

            Request request = new Request.Builder()
                    .url(larkBaseUrl + "/open-apis/im/v1/messages?receive_id_type=open_id")
                    .post(RequestBody.create(objectMapper.writeValueAsString(payload), JSON))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "{}";
                JsonNode node = objectMapper.readTree(body);
                if (node.path("code").asInt() != 0) {
                    log.error("Failed to send Lark message: {}", body);
                }
            }
        } catch (Exception e) {
            log.error("Error sending Lark notification to {}", openId, e);
        }
    }

    private String getAccessToken() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        if (cachedToken.get() != null && tokenExpireAt.get() - now > 300) {
            return cachedToken.get();
        }

        Map<String, String> payload = new HashMap<>();
        payload.put("app_id", appConfig.getAppId());
        payload.put("app_secret", appConfig.getAppSecret());

        Request request = new Request.Builder()
                .url(larkBaseUrl + "/open-apis/auth/v3/tenant_access_token/internal")
                .post(RequestBody.create(objectMapper.writeValueAsString(payload), JSON))
                .header("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "{}";
            JsonNode node = objectMapper.readTree(body);
            String token = node.path("tenant_access_token").asText();
            int expire = node.path("expire").asInt(7200);
            cachedToken.set(token);
            tokenExpireAt.set(System.currentTimeMillis() / 1000 + expire);
            return token;
        }
    }
}
