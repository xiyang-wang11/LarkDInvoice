package com.larkdinvoice.service;

import java.math.BigDecimal;

public interface LarkNotifyService {
    void notifySuccess(String openId, String invoiceNo, BigDecimal amount);
    void notifyFailure(String openId, String reason);
}
