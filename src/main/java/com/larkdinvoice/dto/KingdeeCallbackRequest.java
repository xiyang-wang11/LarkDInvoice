package com.larkdinvoice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * 金蝶发票云按单回调请求体（5.1.03）
 * data 字段可能是 JSON 数组对象，也可能是 base64 编码的字符串，decodeData() 统一处理。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class KingdeeCallbackRequest {

    /** 业务编码：INVOICE.OPEN-开票，INVOICE.CANCEL-作废，INVOICE.RED-红冲 */
    private String interfaceCode;
    /** 返回编码：0-成功，9999-失败 */
    private String returnCode;
    private String returnMsg;
    /**
     * 回调数据：可能是 JSON 数组对象，也可能是 base64 字符串。
     * 用 Object 接收，调用 decodeData() 解析。
     */
    private Object data;
    private Object bizControl;

    public List<CallbackData> decodeData() {
        if (data == null) {
            return Collections.emptyList();
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            String json;
            if (data instanceof String) {
                String str = (String) data;
                try {
                    byte[] decoded = Base64.getDecoder().decode(str);
                    json = new String(decoded, StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    json = str;
                }
            } else {
                json = mapper.writeValueAsString(data);
            }
            return mapper.readValue(json,
                    mapper.getTypeFactory().constructCollectionType(List.class, CallbackData.class));
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CallbackData {
        private String billNo;
        private String invoiceStatus;
        /** 发票代码（全电发票为空，以 invoiceNum 不为空判断成功） */
        private String invoiceCode;
        /** 发票号码 */
        private String invoiceNum;
        private String invoiceDate;
        private String invoiceFileUrl;
        private String invoiceImageUrl;
        private String invoicePdfFileUrl;
        private String invoiceXmlFileUrl;
        private String orderNo;
        /** 开票失败原因 */
        private String issueErrorMessage;
        private String buyerName;
        private String buyerTaxpayerId;
        private BigDecimal totalAmount;
        private String sellerName;
        private String sellerTaxpayerId;
        private String batch;
        private String invoiceType;
    }
}
