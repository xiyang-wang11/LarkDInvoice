package com.larkdinvoice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "lark")
public class AppConfig {

    private String appId;
    private String appSecret;
    private String encryptKey;
    private String approvalCode;
    private FormFields formFields = new FormFields();

    @Data
    public static class FormFields {
        private String buyerName;
        private String buyerTaxNo;
        private String buyerAddressPhone;
        private String buyerBankAccount;
        private String invoiceType;
        private String amount;
        private String items;
    }

    @Data
    @Configuration
    @ConfigurationProperties(prefix = "kingdee")
    public static class KingdeeConfig {
        private String apiUrl;
        private String appKey;
        private String appSecret;
    }
}
