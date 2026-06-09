package com.larkdinvoice.service;

import java.math.BigDecimal;

public interface LarkNotifyService {
    void notifySuccess(String openId, String invoiceNo, BigDecimal amount);
    void notifySuccess(String openId, String invoiceNo, BigDecimal amount, String pdfUrl);
    void notifyFailure(String openId, String reason);
    String getTenantAccessToken() throws Exception;
}
