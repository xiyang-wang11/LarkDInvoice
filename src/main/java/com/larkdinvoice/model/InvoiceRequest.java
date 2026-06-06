package com.larkdinvoice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class InvoiceRequest {
    @JsonProperty("requestNo")
    private String requestNo;

    @JsonProperty("buyerName")
    private String buyerName;

    @JsonProperty("buyerTaxNo")
    private String buyerTaxNo;

    @JsonProperty("buyerAddressPhone")
    private String buyerAddressPhone;

    @JsonProperty("buyerBankAccount")
    private String buyerBankAccount;

    @JsonProperty("invoiceType")
    private String invoiceType;

    @JsonProperty("totalAmount")
    private BigDecimal totalAmount;

    @JsonProperty("items")
    private List<Item> items;

    @Data
    @Builder
    public static class Item {
        @JsonProperty("goodsName")
        private String goodsName;

        @JsonProperty("spec")
        private String spec;

        @JsonProperty("quantity")
        private BigDecimal quantity;

        @JsonProperty("units")
        private String units;

        @JsonProperty("unitPrice")
        private BigDecimal unitPrice;

        @JsonProperty("amount")
        private BigDecimal amount;

        @JsonProperty("taxRate")
        private BigDecimal taxRate;
    }
}
