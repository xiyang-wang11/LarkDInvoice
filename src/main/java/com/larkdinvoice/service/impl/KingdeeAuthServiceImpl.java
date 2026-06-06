package com.larkdinvoice.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkdinvoice.config.AppConfig;
import com.larkdinvoice.dto.kingdee.AppTokenRequest;
import com.larkdinvoice.dto.kingdee.AppTokenResponse;
import com.larkdinvoice.dto.kingdee.LoginRequest;
import com.larkdinvoice.dto.kingdee.LoginResponse;
import com.larkdinvoice.service.KingdeeAuthService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class KingdeeAuthServiceImpl implements KingdeeAuthService {

    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private final AppConfig.KingdeeConfig config;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;
    private final ReentrantLock lock = new ReentrantLock();

    private String cachedAppToken;
    private String cachedAccessToken;
    private LocalDateTime appTokenExpireAt;
    private LocalDateTime accessTokenExpireAt;

    public KingdeeAuthServiceImpl(AppConfig.KingdeeConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String getAppToken() {
        if (cachedAppToken != null && LocalDateTime.now().isBefore(appTokenExpireAt)) {
            return cachedAppToken;
        }
        lock.lock();
        try {
            if (cachedAppToken != null && LocalDateTime.now().isBefore(appTokenExpireAt)) {
                return cachedAppToken;
            }
            return fetchAppToken();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String getAccessToken() {
        if (cachedAccessToken != null && LocalDateTime.now().isBefore(accessTokenExpireAt)) {
            return cachedAccessToken;
        }
        lock.lock();
        try {
            if (cachedAccessToken != null && LocalDateTime.now().isBefore(accessTokenExpireAt)) {
                return cachedAccessToken;
            }
            return fetchAccessToken();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void refreshTokens() {
        lock.lock();
        try {
            cachedAppToken = null;
            cachedAccessToken = null;
            fetchAppToken();
            fetchAccessToken();
        } finally {
            lock.unlock();
        }
    }

    private String fetchAppToken() {
        log.info("正在获取金蝶 appToken，appId={}", config.getAppId());
        AppTokenRequest req = new AppTokenRequest();
        req.setAppId(config.getAppId());
        req.setAppSecret(config.getAppSecret());
        req.setAccountId(config.getAccountId());

        try {
            String respBody = post(config.getGetAppTokenUrl(), req);
            log.info("getAppToken 响应：{}", respBody);
            AppTokenResponse result = objectMapper.readValue(respBody, AppTokenResponse.class);
            if (result == null || !Boolean.TRUE.equals(result.getStatus())) {
                String msg = result != null ? result.getMessage() : "响应为空";
                throw new RuntimeException("获取 appToken 失败：" + msg);
            }
            cachedAppToken = result.getAppToken();
            appTokenExpireAt = LocalDateTime.now().plusMinutes(config.getTokenExpireMinutes());
            log.info("获取 appToken 成功，有效期至 {}", appTokenExpireAt);
            return cachedAppToken;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("调用 getAppToken 接口异常：" + e.getMessage(), e);
        }
    }

    private String fetchAccessToken() {
        log.info("正在获取金蝶 accessToken，user={}", config.getUser());
        String appToken = getAppToken();

        LoginRequest req = new LoginRequest();
        req.setAccountId(config.getAccountId());
        req.setUser(config.getUser());
        req.setAppToken(appToken);
        req.setLoginType("mobile");

        try {
            String respBody = post(config.getLoginUrl(), req);
            log.info("login 响应：{}", respBody);
            LoginResponse result = objectMapper.readValue(respBody, LoginResponse.class);
            if (result == null || !Boolean.TRUE.equals(result.getStatus())) {
                String msg = result != null ? result.getMessage() : "响应为空";
                throw new RuntimeException("获取 accessToken 失败：" + msg);
            }
            cachedAccessToken = result.getAccessToken();
            accessTokenExpireAt = LocalDateTime.now().plusMinutes(config.getTokenExpireMinutes());
            log.info("获取 accessToken 成功，有效期至 {}", accessTokenExpireAt);
            return cachedAccessToken;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("调用 login 接口异常：" + e.getMessage(), e);
        }
    }

    private String post(String url, Object body) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, JSON_TYPE))
                .header("Content-Type", "application/json")
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            return response.body() != null ? response.body().string() : "{}";
        }
    }
}
