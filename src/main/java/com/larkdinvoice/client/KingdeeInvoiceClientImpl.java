package com.larkdinvoice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkdinvoice.config.AppConfig;
import com.larkdinvoice.dto.kingdee.OpenInvoiceRequest;
import com.larkdinvoice.dto.kingdee.OpenInvoiceRequest.BillData;
import com.larkdinvoice.dto.kingdee.OpenInvoiceRequest.BillDetail;
import com.larkdinvoice.dto.kingdee.OpenInvoiceResponse;
import com.larkdinvoice.model.ApprovalForm;
import com.larkdinvoice.model.InvoiceRequest;
import com.larkdinvoice.model.InvoiceResult;
import com.larkdinvoice.service.KingdeeAuthService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
public class KingdeeInvoiceClientImpl implements KingdeeInvoiceClient {

    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_RETRIES = 3;

    private final AppConfig.KingdeeConfig config;
    private final KingdeeAuthService authService;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    public KingdeeInvoiceClientImpl(AppConfig.KingdeeConfig config,
                                     KingdeeAuthService authService,
                                     ObjectMapper objectMapper) {
        this(config, authService, objectMapper, 30);
    }

    KingdeeInvoiceClientImpl(AppConfig.KingdeeConfig config,
                              KingdeeAuthService authService,
                              ObjectMapper objectMapper,
                              int readTimeoutSeconds) {
        this.config = config;
        this.authService = authService;
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public InvoiceResult createInvoice(InvoiceRequest request) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return doPost(request);
            } catch (TokenExpiredException e) {
                log.warn("Token 已过期，刷新后重试（第 {} 次）", attempt);
                authService.refreshTokens();
            } catch (Exception e) {
                log.error("金蝶开票接口调用失败（第 {}/{} 次）：{}", attempt, MAX_RETRIES, e.getMessage(), e);
                if (attempt == MAX_RETRIES) {
                    return failResult("调用金蝶接口异常：" + e.getMessage());
                }
                sleepForRetry(attempt);
            }
        }
        return failResult("调用金蝶接口失败，已重试 " + MAX_RETRIES + " 次");
    }

    private InvoiceResult doPost(InvoiceRequest request) throws Exception {
        String accessToken = authService.getAccessToken();

        OpenInvoiceRequest kingdeeReq = buildKingdeeRequest(request);
        String reqJson = objectMapper.writeValueAsString(kingdeeReq);
        log.info("金蝶开票请求：{}", reqJson);

        Request httpRequest = new Request.Builder()
                .url(config.getOpenInvoiceUrl())
                .post(RequestBody.create(reqJson, JSON_TYPE))
                .header("accessToken", accessToken)
                .header("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            String body = response.body() != null ? response.body().string() : "{}";
            log.info("金蝶开票响应：{}", body);

            OpenInvoiceResponse resp = objectMapper.readValue(body, OpenInvoiceResponse.class);

            if (resp != null && "401".equals(resp.getReturnCode())) {
                throw new TokenExpiredException("token 已过期");
            }

            InvoiceResult result = new InvoiceResult();
            if (resp != null && resp.isSuccess()) {
                result.setSuccess(true);
                result.setInvoiceNo(resp.getInvoiceNum());
            } else {
                result.setSuccess(false);
                result.setErrorMsg(resp != null ? resp.getFailReason() : "响应为空");
            }
            return result;
        }
    }

    private OpenInvoiceRequest buildKingdeeRequest(InvoiceRequest request) throws Exception {
        BillData bill = new BillData();
        bill.setBillNo(request.getRequestNo());
        bill.setInvoiceType(request.getInvoiceType());
        bill.setBuyerName(request.getBuyerName());
        bill.setBuyerTaxpayerId(request.getBuyerTaxNo());
        bill.setBuyerAddressAndTel(request.getBuyerAddressPhone());
        bill.setBuyerBankAndAccount(request.getBuyerBankAccount());
        bill.setTotalAmount(request.getTotalAmount());
        bill.setAutoInvoice("1");
        bill.setIncludeTaxFlag("0");

        if (request.getItems() != null && !request.getItems().isEmpty()) {
            List<BillDetail> details = request.getItems().stream().map(item -> {
                BillDetail d = new BillDetail();
                d.setGoodsName(item.getGoodsName());
                d.setAmount(item.getAmount());
                d.setQuantity(item.getQuantity());
                d.setUnits(item.getUnits());
                d.setPrice(item.getUnitPrice());
                d.setTaxRate(item.getTaxRate() != null ? item.getTaxRate().toPlainString() : null);
                return d;
            }).collect(Collectors.toList());
            bill.setBillDetail(details);
        } else {
            bill.setBillDetail(Collections.emptyList());
        }

        // data 字段：JSON 数组序列化后 Base64 编码
        String json = objectMapper.writeValueAsString(Collections.singletonList(bill));
        log.info("开票请求 data 原文：{}", json);
        String encoded = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));

        OpenInvoiceRequest req = new OpenInvoiceRequest();
        req.setBusinessSystemCode(config.getBusinessSystemCode());
        req.setData(encoded);
        return req;
    }

    private InvoiceResult failResult(String msg) {
        InvoiceResult r = new InvoiceResult();
        r.setSuccess(false);
        r.setErrorMsg(msg);
        return r;
    }

    private void sleepForRetry(int attempt) {
        try {
            Thread.sleep((long) Math.pow(2, attempt - 1) * 1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static class TokenExpiredException extends RuntimeException {
        TokenExpiredException(String msg) { super(msg); }
    }
}
