package com.larkdinvoice.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
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
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

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
            // approval_instance 事件不含表单数据，需通过 API 获取实例详情
            JsonNode instanceDetail = fetchInstanceDetail(instanceCode);
            if (instanceDetail == null) {
                log.error("无法获取审批实例详情，instanceCode：{}", instanceCode);
                processedInstances.remove(instanceCode);
                return;
            }

            // 从详情里拿 open_id 和 form
            String openId = instanceDetail.path("open_id").asText(body.getOpenId());
            String formJson = instanceDetail.path("form").asText();
            log.info("获取审批实例详情成功，instanceCode：{}，openId：{}", instanceCode, openId);

            ApprovalForm form = parseFormFromJson(formJson, instanceCode, openId);
            if (form == null) {
                larkNotifyService.notifyFailure(openId, "审批表单字段不完整，请联系管理员");
                processedInstances.remove(instanceCode);
                return;
            }

            // 开票前存映射：billNo(instanceCode) -> openId + amount，供金蝶异步回调时查找
            pendingInvoiceStore.put(instanceCode, openId, form.getAmount());

            InvoiceRequest request = buildInvoiceRequest(form, instanceCode);
            InvoiceResult result = kingdeeInvoiceClient.createInvoice(request);

            if (result.isSuccess()) {
                log.info("Invoice created synchronously: {}", result.getInvoiceNo());
                pendingInvoiceStore.remove(instanceCode);
                larkNotifyService.notifySuccess(openId, result.getInvoiceNo(), form.getAmount());
            } else if (result.isPending()) {
                log.info("Invoice submitted, waiting for Kingdee callback, instanceCode: {}", instanceCode);
            } else {
                log.error("Invoice creation failed: {}", result.getErrorMsg());
                pendingInvoiceStore.remove(instanceCode);
                larkNotifyService.notifyFailure(openId, result.getErrorMsg());
                processedInstances.remove(instanceCode);
            }
        } catch (Exception e) {
            log.error("Error handling approval event for instance {}", instanceCode, e);
            pendingInvoiceStore.remove(instanceCode);
            processedInstances.remove(instanceCode);
        }
    }

    /**
     * 调飞书 API 获取审批实例详情（含表单数据）
     */
    private JsonNode fetchInstanceDetail(String instanceCode) {
        try {
            String accessToken = larkNotifyService.getTenantAccessToken();
            Request request = new Request.Builder()
                    .url("https://open.feishu.cn/open-apis/approval/v4/instances/" + instanceCode)
                    .header("Authorization", "Bearer " + accessToken)
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "{}";
                log.info("获取审批实例详情响应：{}", body);
                JsonNode root = objectMapper.readTree(body);
                if (root.path("code").asInt() != 0) {
                    log.error("获取审批实例详情失败：{}", body);
                    return null;
                }
                return root.path("data");
            }
        } catch (Exception e) {
            log.error("调用飞书审批实例详情 API 异常：{}", e.getMessage(), e);
            return null;
        }
    }

    private ApprovalForm parseFormFromJson(String formJson, String instanceCode, String openId) {
        try {
            List<Map<String, Object>> formList = objectMapper.readValue(
                    formJson, new TypeReference<List<Map<String, Object>>>() {});

            Map<String, String> fieldMap = formList.stream()
                    .collect(Collectors.toMap(
                            m -> (String) m.get("id"),
                            m -> extractFieldValue(m)));

            log.info("解析表单字段：{}", fieldMap);

            AppConfig.FormFields ff = appConfig.getFormFields();

            // 购方名称：优先取「购方企业名称」字段，否则取「客户/开票名称」
            String buyerName = fieldMap.get(ff.getBuyerCompanyName());
            if (isBlank(buyerName)) {
                buyerName = fieldMap.get(ff.getBuyerName());
            }

            // 购方税号：优先取「购方企业税号」，否则取「税务登记证号」
            String buyerTaxNo = fieldMap.get(ff.getBuyerCompanyTaxNo());
            if (isBlank(buyerTaxNo)) {
                buyerTaxNo = fieldMap.get(ff.getBuyerTaxNo());
            }

            String amount = fieldMap.get(ff.getAmount());

            // 销方名称：从「所属公司」字段取
            String sellerName = fieldMap.get(ff.getSellerName());

            if (isBlank(buyerName) || isBlank(buyerTaxNo) || isBlank(amount)) {
                log.warn("必填字段缺失，instanceCode：{}，buyerName：{}，buyerTaxNo：{}，amount：{}",
                        instanceCode, buyerName, buyerTaxNo, amount);
                return null;
            }

            return ApprovalForm.builder()
                    .instanceCode(instanceCode)
                    .applicantOpenId(openId)
                    .buyerName(buyerName)
                    .buyerTaxNo(buyerTaxNo)
                    .buyerAddressPhone(fieldMap.getOrDefault(ff.getBuyerAddressPhone(), ""))
                    .buyerBankAccount(fieldMap.getOrDefault(ff.getBuyerBankAccount(), ""))
                    .invoiceType(mapInvoiceType(fieldMap.getOrDefault(ff.getInvoiceType(), "10xdp")))
                    .amount(new BigDecimal(amount.trim()))
                    .sellerName(sellerName)
                    .items(parseItems(formList, ff.getItems()))
                    .build();
        } catch (Exception e) {
            log.error("解析审批表单失败，instanceCode：{}", instanceCode, e);
            return null;
        }
    }

    /**
     * 提取字段值：普通字段取 value，radioV2 等选项字段取 option.text
     */
    @SuppressWarnings("unchecked")
    private String extractFieldValue(Map<String, Object> field) {
        Object value = field.get("value");
        if (value == null) return "";
        // radioV2 类型：value 是显示文本，直接用
        if (value instanceof String) return (String) value;
        // number 类型
        if (value instanceof Number) return value.toString();
        // 其他复杂类型转 string
        return value.toString();
    }

    private InvoiceRequest buildInvoiceRequest(ApprovalForm form, String instanceCode) {
        List<InvoiceRequest.Item> items = form.getItems() != null
                ? form.getItems().stream().map(i -> InvoiceRequest.Item.builder()
                        .goodsName(i.getGoodsName())
                        .quantity(i.getQuantity())
                        .unitPrice(i.getUnitPrice())
                        .amount(i.getAmount())
                        .taxRate(i.getTaxRate())
                        .build())
                        .collect(Collectors.toList())
                : Collections.emptyList();

        return InvoiceRequest.builder()
                .requestNo(instanceCode)
                .buyerName(form.getBuyerName())
                .buyerTaxNo(form.getBuyerTaxNo())
                .buyerAddressPhone(form.getBuyerAddressPhone())
                .buyerBankAccount(form.getBuyerBankAccount())
                .invoiceType(form.getInvoiceType())
                .totalAmount(form.getAmount())
                .sellerName(form.getSellerName())
                .items(items)
                .build();
    }

    /**
     * 解析 fieldList 类型的申请明细字段
     * 结构：[[{id, name, value}, ...], ...]  每个子数组是一行明细
     */
    @SuppressWarnings("unchecked")
    private List<ApprovalForm.InvoiceItem> parseItems(List<Map<String, Object>> formList, String itemsFieldId) {
        try {
            Map<String, Object> itemsField = formList.stream()
                    .filter(m -> itemsFieldId.equals(m.get("id")))
                    .findFirst().orElse(null);
            if (itemsField == null) return Collections.emptyList();

            Object value = itemsField.get("value");
            if (!(value instanceof List)) return Collections.emptyList();

            List<List<Map<String, Object>>> rows = (List<List<Map<String, Object>>>) value;
            List<ApprovalForm.InvoiceItem> items = new java.util.ArrayList<>();

            for (List<Map<String, Object>> row : rows) {
                // 每行子字段：id=widget17809209117330001 开票项明细(goodsName)
                //             id=widget17809208366790001 金额(amount)
                Map<String, Object> rowFieldMap = new java.util.HashMap<>();
                for (Map<String, Object> field : row) {
                    rowFieldMap.put((String) field.get("id"), field.get("value"));
                }

                String goodsName = getStr(rowFieldMap, "widget17809209117330001");
                if (isBlank(goodsName)) {
                    goodsName = getStr(rowFieldMap, "widget17809206998000001"); // 业务项目名称
                }
                Object amtObj = rowFieldMap.get("widget17809208366790001");
                BigDecimal amt = amtObj != null ? new BigDecimal(amtObj.toString()) : BigDecimal.ZERO;

                items.add(ApprovalForm.InvoiceItem.builder()
                        .goodsName(goodsName)
                        .quantity(BigDecimal.ONE)
                        .amount(amt)
                        .unitPrice(amt)
                        .taxRate(new BigDecimal("0.06"))
                        .build());
            }
            return items;
        } catch (Exception e) {
            log.warn("解析申请明细失败，使用空明细", e);
            return Collections.emptyList();
        }
    }

    private String getStr(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) return "";
        if (v instanceof Map) {
            // radioV2 option 对象，取 text
            Object text = ((Map<?, ?>) v).get("text");
            return text != null ? text.toString() : "";
        }
        return v.toString();
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * 将飞书表单的发票类型中文名称映射为金蝶接口代码值
     */
    private String mapInvoiceType(String invoiceTypeName) {
        if (invoiceTypeName == null) return "10xdp";
        switch (invoiceTypeName.trim()) {
            case "增值税专用发票": return "10xpp";
            case "增值税普通发票": return "10xdp";
            case "增值税电子专用发票": return "10xzp";
            case "增值税电子普通发票": return "10xdp";
            case "全电专用发票": return "10xpp";
            case "全电普通发票": return "10xdp";
            default:
                // 如果已经是代码值（如 10xdp），直接返回
                if (invoiceTypeName.startsWith("10x") || invoiceTypeName.startsWith("08x")) {
                    return invoiceTypeName;
                }
                log.warn("未知发票类型：{}，使用默认值 10xdp", invoiceTypeName);
                return "10xdp";
        }
    }
}
