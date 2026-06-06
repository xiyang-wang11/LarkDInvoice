package com.larkdinvoice.store;

import lombok.Data;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存存储：billNo -> 审批发起人信息
 * 用于金蝶异步回调时找到对应的飞书 openId，发送通知。
 * 服务重启后数据丢失，丢失后回调无法通知飞书，但发票本身仍正常开具。
 */
@Component
public class PendingInvoiceStore {

    private final ConcurrentHashMap<String, PendingEntry> store = new ConcurrentHashMap<>();

    public void put(String billNo, String openId, BigDecimal amount) {
        store.put(billNo, new PendingEntry(openId, amount));
    }

    public PendingEntry get(String billNo) {
        return store.get(billNo);
    }

    public void remove(String billNo) {
        store.remove(billNo);
    }

    @Data
    public static class PendingEntry {
        private final String openId;
        private final BigDecimal amount;
    }
}
