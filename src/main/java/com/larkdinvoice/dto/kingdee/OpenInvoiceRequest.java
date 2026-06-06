package com.larkdinvoice.dto.kingdee;

import lombok.Data;

import java.util.List;

@Data
public class OpenInvoiceRequest {

    private String interfaceCode = "BILL.PUSH";
    private String businessSystemCode;
    /** Base64 编码的 JSON 数组（BillData 列表）*/
    private String data;

    @Data
    public static class BillData {
        private String billNo;
        private String billDate;
        private Object totalAmount;
        private String autoInvoice;
        private String includeTaxFlag;
        private String invoiceType;
        private String buyerName;
        private String buyerTaxpayerId;
        private String buyerProperty;
        private String buyerRecipientMail;
        private String buyerBankAndAccount;
        private String buyerAddressAndTel;
        private String sellerName;
        private String sellerTaxpayerId;
        private String sellerBankAndAccount;
        private String sellerAddressAndTel;
        private String drawer;
        private String autoMerge = "0";
        private String remark;
        private List<BillDetail> billDetail;
    }

    @Data
    public static class BillDetail {
        private Object amount;
        private String detailId;
        private String goodsCode;
        private String goodsName;
        private Integer lineProperty;
        private String revenueCode;
        private String taxRate;
        private Object quantity;
        private String units;
        private String specification;
        private Object price;
        private Object taxAmount;
        private String privilegeFlag;
        private String privilegeContent;
        private Object discountAmount;
        private String remark;
    }
}
