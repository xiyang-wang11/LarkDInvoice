package com.larkdinvoice.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkdinvoice.client.KingdeeInvoiceClient;
import com.larkdinvoice.config.AppConfig;
import com.larkdinvoice.model.ApprovalForm;
import com.larkdinvoice.model.InvoiceRequest;
import com.larkdinvoice.model.InvoiceResult;
import com.larkdinvoice.model.LarkEvent;
import com.larkdinvoice.service.LarkNotifyService;
import com.larkdinvoice.store.PendingInvoiceStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalEventHandlerImpl implements ApprovalEventHandler {

    private final AppConfig appConfig;
    private final KingdeeInvoiceClient kingdeeInvoiceClient;
    private final LarkNotifyService larkNotifyService;
    private final ObjectMapper objectMapper;
    private final PendingInvoiceStore pendingInvoiceStore;

    private final Set<String> processedInstances = ConcurrentHashMap.newKeySet();

    @Override
    @Async("webhookExecutor")
    public void handle(LarkEvent event) {
        LarkEvent.EventBody body = event.getEvent();
        if (body == null) {
            return;
        }

        if (!appConfig.getApprovalCode().equals(body.getApprovalCode())) {
            return;
        }

        if (!"APPROVED".equals(body.getStatus())) {
            return;
        }

        String instanceCode = body.getInstanceCode();
        if (!processedInstances.add(instanceCode)) {
            log.info("Duplicate approval instance {}, skipping", instanceCode);
            return;
        }

        try {
            ApprovalForm form = parseForm(body);
            if (form == null) {
                larkNotifyService.notifyFailure(body.getOpenId(), "审批表单字段不完整，请联系管理员");
                return;
            }

            // 开票前存映射：billNo(instanceCode) -> openId + amount，供金蝶异步回调时查找
            pendingInvoiceStore.put(instanceCode, body.getOpenId(), form.getAmount());

            InvoiceRequest request = buildInvoiceRequest(form, instanceCode);
            InvoiceResult result = kingdeeInvoiceClient.createInvoice(request);

            if (result.isSuccess()) {
                // 金蝶同步直接返回发票号（部分环境），直接通知
                log.info("Invoice created synchronously: {}", result.getInvoiceNo());
                pendingInvoiceStore.remove(instanceCode);
                larkNotifyService.notifySuccess(body.getOpenId(), result.getInvoiceNo(), form.getAmount());
            } else if (result.isPending()) {
                // 金蝶异步处理中，等待回调
                log.info("Invoice submitted, waiting for Kingdee callback, instanceCode: {}", instanceCode);
            } else {
                // 明确失败
                log.error("Invoice creation failed: {}", result.getErrorMsg());
                pendingInvoiceStore.remove(instanceCode);
                larkNotifyService.notifyFailure(body.getOpenId(), result.getErrorMsg());
                processedInstances.remove(instanceCode);
            }
        } catch (Exception e) {
            log.error("Error handling approval event for instance {}", instanceCode, e);
            pendingInvoiceStore.remove(instanceCode);
            larkNotifyService.notifyFailure(body.getOpenId(), "系统异常，请联系管理员");
            processedInstances.remove(instanceCode);
        }
    }

    private ApprovalForm parseForm(LarkEvent.EventBody body) {
        try {
            List<Map<String, Object>> formList = objectMapper.readValue(
                    body.getForm(), new TypeReference<List<Map<String, Object>>>() {});
            Map<String, String> fieldMap = formList.stream()
                    .collect(Collectors.toMap(
                            m -> (String) m.get("id"),
                            m -> String.valueOf(m.getOrDefault("value", ""))));

            AppConfig.FormFields ff = appConfig.getFormFields();
            String buyerName = fieldMap.get(ff.getBuyerName());
            String buyerTaxNo = fieldMap.get(ff.getBuyerTaxNo());
            String amount = fieldMap.get(ff.getAmount());

            if (isBlank(buyerName) || isBlank(buyerTaxNo) || isBlank(amount)) {
                log.warn("Required form fields missing for instance {}", body.getInstanceCode());
                return null;
            }

            return ApprovalForm.builder()
                    .instanceCode(body.getInstanceCode())
                    .applicantOpenId(body.getOpenId())
                    .buyerName(buyerName)
                    .buyerTaxNo(buyerTaxNo)
                    .buyerAddressPhone(fieldMap.getOrDefault(ff.getBuyerAddressPhone(), ""))
                    .buyerBankAccount(fieldMap.getOrDefault(ff.getBuyerBankAccount(), ""))
                    .invoiceType(fieldMap.getOrDefault(ff.getInvoiceType(), "普通发票"))
                    .amount(new BigDecimal(amount))
                    .items(Collections.emptyList())
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse approval form", e);
            return null;
        }
    }

    private InvoiceRequest buildInvoiceRequest(ApprovalForm form, String instanceCode) {
        return InvoiceRequest.builder()
                .requestNo(instanceCode)
                .buyerName(form.getBuyerName())
                .buyerTaxNo(form.getBuyerTaxNo())
                .buyerAddressPhone(form.getBuyerAddressPhone())
                .buyerBankAccount(form.getBuyerBankAccount())
                .invoiceType(form.getInvoiceType())
                .totalAmount(form.getAmount())
                .items(Collections.emptyList())
                .build();
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
