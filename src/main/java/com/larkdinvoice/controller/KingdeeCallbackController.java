package com.larkdinvoice.controller;

import com.larkdinvoice.dto.KingdeeCallbackRequest;
import com.larkdinvoice.dto.KingdeeCallbackRequest.CallbackData;
import com.larkdinvoice.dto.KingdeeCallbackResponse;
import com.larkdinvoice.service.LarkNotifyService;
import com.larkdinvoice.store.PendingInvoiceStore;
import com.larkdinvoice.store.PendingInvoiceStore.PendingEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 金蝶发票云异步回调接口（5.1.03）
 *
 * 金蝶开票完成后主动回调本接口，本接口查找对应的飞书审批发起人并发送通知。
 * 无论内部处理是否异常，必须返回 {"success":true,"code":"0","message":"success"}，
 * 否则金蝶会无限重试。
 *
 * 回调地址需在金蝶后台配置：https://your-domain/webhook/kingdee/callback
 */
@Slf4j
@RestController
@RequestMapping("/webhook/kingdee")
@RequiredArgsConstructor
public class KingdeeCallbackController {

    private final LarkNotifyService larkNotifyService;
    private final PendingInvoiceStore pendingInvoiceStore;

    @PostMapping("/callback")
    public KingdeeCallbackResponse callback(@RequestBody String rawBody) {
        log.info("收到金蝶回调原始请求体：{}", rawBody);

        KingdeeCallbackRequest req;
        try {
            req = new com.fasterxml.jackson.databind.ObjectMapper().readValue(rawBody, KingdeeCallbackRequest.class);
        } catch (Exception e) {
            log.error("解析金蝶回调请求体失败：{}", e.getMessage());
            return KingdeeCallbackResponse.ok();
        }

        log.info("收到金蝶回调，interfaceCode：{}，returnCode：{}，data类型：{}",
                req.getInterfaceCode(), req.getReturnCode(),
                req.getData() != null ? req.getData().getClass().getSimpleName() : "null");

        try {
            List<CallbackData> dataList = req.decodeData();
            log.info("回调 data 解析条数：{}", dataList.size());

            if (dataList.isEmpty()) {
                log.warn("回调 data 为空，interfaceCode：{}", req.getInterfaceCode());
                return KingdeeCallbackResponse.ok();
            }

            for (CallbackData item : dataList) {
                processCallbackItem(item);
            }

        } catch (Exception e) {
            log.error("处理金蝶回调异常：{}", e.getMessage(), e);
            // 即使异常也返回 success，避免金蝶无限重试，异常已记录日志可人工介入
        }

        return KingdeeCallbackResponse.ok();
    }

    private void processCallbackItem(CallbackData item) {
        String billNo = item.getBillNo();

        // 查找飞书审批发起人信息
        PendingEntry entry = pendingInvoiceStore.get(billNo);
        log.info("回调查找 PendingStore，billNo：{}，entry：{}", billNo, entry != null ? entry.getOpenId() : "null");
        if (entry == null) {
            log.warn("回调找不到对应的飞书发起人，billNo：{}，可能已处理或服务重启过", billNo);
            return;
        }

        // invoiceCode 或 invoiceNum 不为空表示开票成功（全电发票无 invoiceCode，仅有 invoiceNum）
        boolean success = StringUtils.hasText(item.getInvoiceCode())
                || StringUtils.hasText(item.getInvoiceNum());

        if (success) {
            log.info("金蝶回调开票成功，单据号：{}，发票号码：{}，发票代码：{}",
                    billNo, item.getInvoiceNum(), item.getInvoiceCode());
            pendingInvoiceStore.remove(billNo);
            String invoiceNo = StringUtils.hasText(item.getInvoiceNum())
                    ? item.getInvoiceNum() : item.getInvoiceCode();
            larkNotifyService.notifySuccess(entry.getOpenId(), invoiceNo, entry.getAmount(),
                    item.getInvoicePdfFileUrl());
        } else {
            String failReason = StringUtils.hasText(item.getIssueErrorMessage())
                    ? item.getIssueErrorMessage() : "开票失败，无错误信息";
            log.warn("金蝶回调开票失败，单据号：{}，原因：{}", billNo, failReason);
            pendingInvoiceStore.remove(billNo);
            larkNotifyService.notifyFailure(entry.getOpenId(), failReason);
        }
    }
}
