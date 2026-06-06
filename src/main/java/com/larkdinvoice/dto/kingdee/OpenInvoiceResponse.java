package com.larkdinvoice.dto.kingdee;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenInvoiceResponse {

    private String errorCode;
    private String message;
    private Boolean status;
    private Boolean success;

    /** 业务编码，开票回调是 INVOICE.OPEN */
    private String interfaceCode;
    /** 返回编码：0-成功，9999-失败 */
    private String returnCode;
    /** 返回信息 */
    private String returnMsg;
    /** 发票状态：0-正常，2-部分红冲，3-红冲，6-作废 */
    private String invoiceStatus;
    private String invoiceCode;
    private String invoiceNum;
    private String invoiceDate;
    private String invoiceFileUrl;
    private String invoiceImageUrl;
    private String invoicePdfFileUrl;
    private String invoiceXmlFileUrl;
    private String orderNo;

    public boolean isSuccess() {
        return Boolean.TRUE.equals(status) || Boolean.TRUE.equals(success);
    }

    public String getFailReason() {
        return message != null ? message : errorCode;
    }
}
