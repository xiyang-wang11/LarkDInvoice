package com.larkdinvoice.controller;

import com.larkdinvoice.dto.KingdeeCallbackRequest;
import com.larkdinvoice.dto.KingdeeCallbackRequest.CallbackData;
import com.larkdinvoice.dto.KingdeeCallbackResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 金蝶发票云异步回调接口（5.1.03）
 *
 * 金蝶开票为异步流程，开票完成后主动回调本接口。
 * 无论内部处理是否异常，必须返回 {"success":true,"code":"0","message":"success"}，
 * 否则金蝶会无限重试。
 *
 * 回调地址需在金蝶后台配置：https://your-domain/webhook/kingdee/callback
 */
@Slf4j
@RestController
@RequestMapping("/webhook/kingdee")
public class KingdeeCallbackController {

    @PostMapping("/callback")
    public KingdeeCallbackResponse callback(@RequestBody KingdeeCallbackRequest req) {
        log.info("收到金蝶回调，interfaceCode：{}，returnCode：{}",
                req.getInterfaceCode(), req.getReturnCode());

        try {
            List<CallbackData> dataList = req.decodeData();
            log.info("回调 data 解析条数：{}", dataList.size());

            if (dataList.isEmpty()) {
                log.warn("回调 data 为空，interfaceCode：{}", req.getInterfaceCode());
                return KingdeeCallbackResponse.ok();
            }

            for (CallbackData item : dataList) {
                processCallbackItem(req.getInterfaceCode(), item);
            }

        } catch (Exception e) {
            log.error("处理金蝶回调异常：{}", e.getMessage(), e);
            // 即使异常也返回 success，避免金蝶无限重试，异常已记录日志可人工介入
        }

        return KingdeeCallbackResponse.ok();
    }

    private void processCallbackItem(String interfaceCode, CallbackData item) {
        String billNo = item.getBillNo();
        // invoiceCode 或 invoiceNum 不为空表示开票成功（全电发票无 invoiceCode，仅有 invoiceNum）
        boolean success = StringUtils.hasText(item.getInvoiceCode())
                || StringUtils.hasText(item.getInvoiceNum());

        if (success) {
            log.info("金蝶回调开票成功，单据号：{}，发票号码：{}，发票代码：{}",
                    billNo, item.getInvoiceNum(), item.getInvoiceCode());
            // TODO: 将开票结果持久化到数据库或通知飞书发起人
            // 目前项目无数据库，可通过 LarkNotifyService 通知审批发起人（需关联 instanceCode）
        } else {
            String failReason = StringUtils.hasText(item.getIssueErrorMessage())
                    ? item.getIssueErrorMessage() : "开票失败，无错误信息";
            log.warn("金蝶回调开票失败，单据号：{}，原因：{}", billNo, failReason);
            // TODO: 通知飞书审批发起人开票失败
        }
    }
}
