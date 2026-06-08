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
        /** 购方企业名称（用于开票名称） */
        private String buyerCompanyName;
        /** 购方企业税号 */
        private String buyerCompanyTaxNo;
        /** 销方公司名称（所属公司字段） */
        private String sellerName;
    }

    @Data
    @Configuration
    @ConfigurationProperties(prefix = "kingdee")
    public static class KingdeeConfig {
        /** getAppToken 接口地址 */
        private String getAppTokenUrl;
        /** login 接口地址 */
        private String loginUrl;
        /** 开票接口地址 */
        private String openInvoiceUrl;
        private String appId;
        private String appSecret;
        private String accountId;
        /** 登录用户（手机号） */
        private String user;
        /** businessSystemCode，用于开票请求 */
        private String businessSystemCode;
        /** 默认销方税号（固定值，从配置读取） */
        private String sellerTaxpayerId;
        /** Token 缓存时间（分钟），默认 55 */
        private int tokenExpireMinutes = 55;
    }
}
