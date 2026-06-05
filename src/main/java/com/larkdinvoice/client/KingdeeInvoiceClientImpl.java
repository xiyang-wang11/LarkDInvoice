package com.larkdinvoice.client;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkdinvoice.config.AppConfig;
import com.larkdinvoice.model.InvoiceRequest;
import com.larkdinvoice.model.InvoiceResult;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class KingdeeInvoiceClientImpl implements KingdeeInvoiceClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_RETRIES = 3;

    private final AppConfig.KingdeeConfig config;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    public KingdeeInvoiceClientImpl(AppConfig.KingdeeConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public InvoiceResult createInvoice(InvoiceRequest request) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String json = objectMapper.writeValueAsString(request);
                String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
                String signature = sign(timestamp, json);

                Request httpRequest = new Request.Builder()
                        .url(config.getApiUrl() + "invoice/create")
                        .post(RequestBody.create(json, JSON))
                        .header("X-App-Key", config.getAppKey())
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", signature)
                        .header("Content-Type", "application/json")
                        .build();

                try (Response response = httpClient.newCall(httpRequest).execute()) {
                    String body = response.body() != null ? response.body().string() : "{}";
                    return objectMapper.readValue(body, InvoiceResult.class);
                }
            } catch (SocketTimeoutException e) {
                log.warn("Kingdee API timeout on attempt {}/{}", attempt, MAX_RETRIES);
                if (attempt == MAX_RETRIES) {
                    InvoiceResult result = new InvoiceResult();
                    result.setSuccess(false);
                    result.setErrorMsg("timeout: Kingdee API did not respond in time");
                    return result;
                }
                sleepForRetry(attempt);
            } catch (Exception e) {
                log.error("Kingdee API call failed on attempt {}/{}", attempt, MAX_RETRIES, e);
                if (attempt == MAX_RETRIES) {
                    InvoiceResult result = new InvoiceResult();
                    result.setSuccess(false);
                    result.setErrorMsg("调用金蝶接口异常：" + e.getMessage());
                    return result;
                }
                sleepForRetry(attempt);
            }
        }
        InvoiceResult result = new InvoiceResult();
        result.setSuccess(false);
        result.setErrorMsg("调用金蝶接口失败，已重试" + MAX_RETRIES + "次");
        return result;
    }

    private String sign(String timestamp, String body) {
        String content = config.getAppKey() + timestamp + body + config.getAppSecret();
        return DigestUtil.sha256Hex(content);
    }

    private void sleepForRetry(int attempt) {
        try {
            Thread.sleep((long) Math.pow(2, attempt - 1) * 1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
