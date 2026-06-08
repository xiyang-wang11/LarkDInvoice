package com.larkdinvoice.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class ApprovalForm {
    private String instanceCode;
    private String applicantOpenId;
    private String buyerName;
    private String buyerTaxNo;
    private String buyerAddressPhone;
    private String buyerBankAccount;
    private String invoiceType;
    private BigDecimal amount;
    private String sellerName;
    private List<InvoiceItem> items;

    @Data
    @Builder
    public static class InvoiceItem {
        private String goodsName;
        private String spec;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal amount;
        private BigDecimal taxRate;
    }
}
