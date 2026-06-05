# 飞书审批自动开票 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 飞书应收单审批通过后，自动调用金蝶发票云 API 完成开票，并将结果通过飞书消息通知审批发起人。

**Architecture:** 单体 Spring Boot 3.x 服务，Webhook 接收飞书事件后立即返回 200，异步线程处理业务逻辑（验签→过滤→提取字段→调用金蝶→飞书通知）。用 ConcurrentHashMap 保证幂等，防止飞书重试导致重复开票。

**Tech Stack:** Spring Boot 3.x, Spring Web, Spring Async, OkHttp3, Jackson, Lombok, Hutool-crypto 5.x, JUnit 5, Mockito

---

## 文件结构

```
pom.xml
src/
  main/
    java/com/larkdinvoice/
      LarkDInvoiceApplication.java
      config/
        AppConfig.java          # 配置属性绑定（@ConfigurationProperties）
        AsyncConfig.java        # 异步线程池配置
      controller/
        WebhookController.java  # POST /webhook/lark，验签，异步分发
      handler/
        ApprovalEventHandler.java  # 过滤+提取表单+调用开票+通知
      client/
        KingdeeInvoiceClient.java  # 封装金蝶发票云 HTTP API
      service/
        LarkNotifyService.java     # 飞书消息发送 + token 管理
      model/
        LarkEvent.java             # 飞书 Webhook 事件结构
        ApprovalForm.java          # 解析后的审批表单字段
        InvoiceRequest.java        # 金蝶开票请求结构
        InvoiceResult.java         # 金蝶开票结果
  test/
    java/com/larkdinvoice/
      controller/WebhookControllerTest.java
      handler/ApprovalEventHandlerTest.java
      client/KingdeeInvoiceClientTest.java
      service/LarkNotifyServiceTest.java
  resources/
    application.yml
    application-test.yml
```

---

## Task 1: 初始化 Spring Boot 项目

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/larkdinvoice/LarkDInvoiceApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/test/resources/application-test.yml`

- [ ] **Step 1: 创建 pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.5</version>
    </parent>

    <groupId>com.larkdinvoice</groupId>
    <artifactId>lark-d-invoice</artifactId>
    <version>1.0.0-SNAPSHOT</version>

    <properties>
        <java.version>17</java.version>
        <hutool.version>5.8.26</hutool.version>
        <okhttp.version>4.12.0</okhttp.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>cn.hutool</groupId>
            <artifactId>hutool-crypto</artifactId>
            <version>${hutool.version}</version>
        </dependency>
        <dependency>
            <groupId>com.squareup.okhttp3</groupId>
            <artifactId>okhttp</artifactId>
            <version>${okhttp.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.squareup.okhttp3</groupId>
            <artifactId>mockwebserver</artifactId>
            <version>${okhttp.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: 创建启动类**

`src/main/java/com/larkdinvoice/LarkDInvoiceApplication.java`:
```java
package com.larkdinvoice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class LarkDInvoiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(LarkDInvoiceApplication.class, args);
    }
}
```

- [ ] **Step 3: 创建 application.yml**

`src/main/resources/application.yml`:
```yaml
server:
  port: 8080

lark:
  app-id: ${LARK_APP_ID:your-app-id}
  app-secret: ${LARK_APP_SECRET:your-app-secret}
  encrypt-key: ${LARK_ENCRYPT_KEY:your-encrypt-key}
  approval-code: ${LARK_APPROVAL_CODE:your-approval-code}
  form-fields:
    buyer-name: field_buyer_name
    buyer-tax-no: field_buyer_tax_no
    buyer-address-phone: field_buyer_address_phone
    buyer-bank-account: field_buyer_bank_account
    invoice-type: field_invoice_type
    amount: field_amount
    items: field_items

kingdee:
  api-url: ${KINGDEE_API_URL:https://api.kingdee.com}
  app-key: ${KINGDEE_APP_KEY:your-app-key}
  app-secret: ${KINGDEE_APP_SECRET:your-app-secret}
```

- [ ] **Step 4: 创建测试配置**

`src/test/resources/application-test.yml`:
```yaml
lark:
  app-id: test-app-id
  app-secret: test-app-secret
  encrypt-key: test-encrypt-key
  approval-code: test-approval-code
  form-fields:
    buyer-name: field_buyer_name
    buyer-tax-no: field_buyer_tax_no
    buyer-address-phone: field_buyer_address_phone
    buyer-bank-account: field_buyer_bank_account
    invoice-type: field_invoice_type
    amount: field_amount
    items: field_items

kingdee:
  api-url: http://localhost:9999
  app-key: test-app-key
  app-secret: test-app-secret
```

- [ ] **Step 5: 验证项目编译**

```bash
mvn compile -q
```
期望输出：无错误，`BUILD SUCCESS`

- [ ] **Step 6: 提交**

```bash
git add pom.xml src/main/java/com/larkdinvoice/LarkDInvoiceApplication.java src/main/resources/application.yml src/test/resources/application-test.yml
git commit -m "chore: init Spring Boot project structure"
```

---

## Task 2: 配置属性类与异步线程池

**Files:**
- Create: `src/main/java/com/larkdinvoice/config/AppConfig.java`
- Create: `src/main/java/com/larkdinvoice/config/AsyncConfig.java`

- [ ] **Step 1: 创建 AppConfig.java**

`src/main/java/com/larkdinvoice/config/AppConfig.java`:
```java
package com.larkdinvoice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

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
```

- [ ] **Step 2: 创建 AsyncConfig.java**

`src/main/java/com/larkdinvoice/config/AsyncConfig.java`:
```java
package com.larkdinvoice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    @Bean(name = "webhookExecutor")
    public Executor webhookExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("webhook-");
        executor.initialize();
        return executor;
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
mvn compile -q
```
期望：`BUILD SUCCESS`

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/larkdinvoice/config/
git commit -m "feat: add configuration properties and async thread pool"
```

---

## Task 3: 数据模型

**Files:**
- Create: `src/main/java/com/larkdinvoice/model/LarkEvent.java`
- Create: `src/main/java/com/larkdinvoice/model/ApprovalForm.java`
- Create: `src/main/java/com/larkdinvoice/model/InvoiceRequest.java`
- Create: `src/main/java/com/larkdinvoice/model/InvoiceResult.java`

- [ ] **Step 1: 创建 LarkEvent.java（飞书事件结构）**

`src/main/java/com/larkdinvoice/model/LarkEvent.java`:
```java
package com.larkdinvoice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LarkEvent {

    // URL 验证握手
    private String challenge;
    private String token;
    private String type;

    // 事件封装（2.0格式）
    private Schema schema;
    private Header header;
    private EventBody event;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Schema {
        private String schema;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Header {
        @JsonProperty("event_id")
        private String eventId;
        @JsonProperty("event_type")
        private String eventType;
        @JsonProperty("create_time")
        private String createTime;
        @JsonProperty("token")
        private String token;
        @JsonProperty("app_id")
        private String appId;
        @JsonProperty("tenant_key")
        private String tenantKey;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EventBody {
        @JsonProperty("approval_code")
        private String approvalCode;
        @JsonProperty("instance_code")
        private String instanceCode;
        @JsonProperty("status")
        private String status;
        @JsonProperty("user_id")
        private String userId;
        @JsonProperty("open_id")
        private String openId;
        @JsonProperty("form")
        private String form; // JSON 字符串，需二次解析
        @JsonProperty("timeline")
        private List<Map<String, Object>> timeline;
    }
}
```

- [ ] **Step 2: 创建 ApprovalForm.java（审批表单字段）**

`src/main/java/com/larkdinvoice/model/ApprovalForm.java`:
```java
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
    private List<InvoiceItem> items;

    @Data
    @Builder
    public static class InvoiceItem {
        private String goodsName;
        private String spec;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal taxRate;
    }
}
```

- [ ] **Step 3: 创建 InvoiceRequest.java（金蝶请求结构）**

`src/main/java/com/larkdinvoice/model/InvoiceRequest.java`:
```java
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
    private String requestNo; // 使用 instanceCode 保证幂等

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

        @JsonProperty("unitPrice")
        private BigDecimal unitPrice;

        @JsonProperty("taxRate")
        private BigDecimal taxRate;
    }
}
```

- [ ] **Step 4: 创建 InvoiceResult.java（金蝶返回结构）**

`src/main/java/com/larkdinvoice/model/InvoiceResult.java`:
```java
package com.larkdinvoice.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class InvoiceResult {

    @JsonProperty("success")
    private boolean success;

    @JsonProperty("invoiceNo")
    private String invoiceNo;

    @JsonProperty("errorCode")
    private String errorCode;

    @JsonProperty("errorMsg")
    private String errorMsg;
}
```

- [ ] **Step 5: 编译验证**

```bash
mvn compile -q
```
期望：`BUILD SUCCESS`

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/larkdinvoice/model/
git commit -m "feat: add data models for Lark event and invoice"
```

---

## Task 4: WebhookController（验签 + 事件分发）

**Files:**
- Create: `src/main/java/com/larkdinvoice/controller/WebhookController.java`
- Create: `src/test/java/com/larkdinvoice/controller/WebhookControllerTest.java`

- [ ] **Step 1: 编写失败测试**

`src/test/java/com/larkdinvoice/controller/WebhookControllerTest.java`:
```java
package com.larkdinvoice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkdinvoice.handler.ApprovalEventHandler;
import com.larkdinvoice.model.LarkEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WebhookController.class)
@ActiveProfiles("test")
class WebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ApprovalEventHandler approvalEventHandler;

    @Test
    void shouldReturnChallengeForUrlVerification() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "challenge", "test-challenge-value",
                "token", "test-token",
                "type", "url_verification"
        ));

        mockMvc.perform(post("/webhook/lark")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Lark-Signature", "dummy")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").value("test-challenge-value"));
    }

    @Test
    void shouldReturn403WhenSignatureInvalid() throws Exception {
        String body = "{\"schema\":\"2.0\",\"header\":{\"event_type\":\"approval_instance\"}}";

        mockMvc.perform(post("/webhook/lark")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Lark-Signature", "invalid-signature")
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturn200AndDelegateToHandlerWhenSignatureValid() throws Exception {
        // 构造合法请求体和签名
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = "test-nonce";
        String encryptKey = "test-encrypt-key";
        String body = objectMapper.writeValueAsString(Map.of(
                "schema", "2.0",
                "header", Map.of("event_type", "approval_instance")
        ));
        String signature = computeSignature(timestamp, nonce, encryptKey, body);

        mockMvc.perform(post("/webhook/lark")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Lark-Signature", signature)
                        .header("X-Lark-Request-Timestamp", timestamp)
                        .header("X-Lark-Request-Nonce", nonce)
                        .content(body))
                .andExpect(status().isOk());

        verify(approvalEventHandler, timeout(1000)).handle(any(LarkEvent.class));
    }

    private String computeSignature(String timestamp, String nonce, String encryptKey, String body) {
        String content = timestamp + nonce + encryptKey + body;
        return cn.hutool.crypto.digest.DigestUtil.sha256Hex(content);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -pl . -Dtest=WebhookControllerTest -q 2>&1 | tail -20
```
期望：编译错误或测试失败（`WebhookController` 尚未创建）

- [ ] **Step 3: 实现 WebhookController**

`src/main/java/com/larkdinvoice/controller/WebhookController.java`:
```java
package com.larkdinvoice.controller;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkdinvoice.config.AppConfig;
import com.larkdinvoice.handler.ApprovalEventHandler;
import com.larkdinvoice.model.LarkEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final AppConfig appConfig;
    private final ApprovalEventHandler approvalEventHandler;
    private final ObjectMapper objectMapper;

    @PostMapping("/lark")
    public ResponseEntity<?> receive(
            @RequestHeader(value = "X-Lark-Signature", required = false) String signature,
            @RequestHeader(value = "X-Lark-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Lark-Request-Nonce", required = false) String nonce,
            @RequestBody String rawBody) {

        LarkEvent event;
        try {
            event = objectMapper.readValue(rawBody, LarkEvent.class);
        } catch (Exception e) {
            log.warn("Failed to parse Lark event body", e);
            return ResponseEntity.badRequest().build();
        }

        // URL 验证握手，跳过签名验证
        if ("url_verification".equals(event.getType())) {
            return ResponseEntity.ok(Map.of("challenge", event.getChallenge()));
        }

        // 正常事件，验签
        if (!verifySignature(timestamp, nonce, rawBody, signature)) {
            log.warn("Lark webhook signature verification failed");
            return ResponseEntity.status(403).build();
        }

        // 异步处理，立即返回 200
        approvalEventHandler.handle(event);
        return ResponseEntity.ok().build();
    }

    private boolean verifySignature(String timestamp, String nonce, String body, String signature) {
        if (timestamp == null || nonce == null || signature == null) {
            return false;
        }
        String content = timestamp + nonce + appConfig.getEncryptKey() + body;
        String expected = DigestUtil.sha256Hex(content);
        return expected.equals(signature);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn test -pl . -Dtest=WebhookControllerTest -q 2>&1 | tail -20
```
期望：`Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/larkdinvoice/controller/ src/test/java/com/larkdinvoice/controller/
git commit -m "feat: add WebhookController with signature verification"
```

---

## Task 5: ApprovalEventHandler（审批事件处理）

**Files:**
- Create: `src/main/java/com/larkdinvoice/handler/ApprovalEventHandler.java`
- Create: `src/test/java/com/larkdinvoice/handler/ApprovalEventHandlerTest.java`

- [ ] **Step 1: 编写失败测试**

`src/test/java/com/larkdinvoice/handler/ApprovalEventHandlerTest.java`:
```java
package com.larkdinvoice.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkdinvoice.client.KingdeeInvoiceClient;
import com.larkdinvoice.config.AppConfig;
import com.larkdinvoice.model.*;
import com.larkdinvoice.service.LarkNotifyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApprovalEventHandlerTest {

    @Mock
    private AppConfig appConfig;
    @Mock
    private KingdeeInvoiceClient kingdeeInvoiceClient;
    @Mock
    private LarkNotifyService larkNotifyService;
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private ApprovalEventHandler handler;

    private AppConfig.FormFields formFields;

    @BeforeEach
    void setUp() {
        formFields = new AppConfig.FormFields();
        formFields.setBuyerName("field_buyer_name");
        formFields.setBuyerTaxNo("field_buyer_tax_no");
        formFields.setBuyerAddressPhone("field_buyer_address_phone");
        formFields.setBuyerBankAccount("field_buyer_bank_account");
        formFields.setInvoiceType("field_invoice_type");
        formFields.setAmount("field_amount");
        formFields.setItems("field_items");

        when(appConfig.getApprovalCode()).thenReturn("test-approval-code");
        when(appConfig.getFormFields()).thenReturn(formFields);
    }

    @Test
    void shouldIgnoreNonTargetApprovalCode() {
        LarkEvent event = buildEvent("other-approval-code", "APPROVED");
        handler.handle(event);
        verifyNoInteractions(kingdeeInvoiceClient, larkNotifyService);
    }

    @Test
    void shouldIgnoreNonApprovedStatus() {
        LarkEvent event = buildEvent("test-approval-code", "PENDING");
        handler.handle(event);
        verifyNoInteractions(kingdeeInvoiceClient, larkNotifyService);
    }

    @Test
    void shouldNotifySuccessWhenInvoiceCreated() throws Exception {
        LarkEvent event = buildEvent("test-approval-code", "APPROVED");
        event.getEvent().setForm(buildFormJson());

        when(objectMapper.readValue(anyString(), eq(List.class))).thenReturn(buildFormList());

        InvoiceResult result = new InvoiceResult();
        result.setSuccess(true);
        result.setInvoiceNo("INV-2026-001");
        when(kingdeeInvoiceClient.createInvoice(any())).thenReturn(result);

        handler.handle(event);

        verify(larkNotifyService).notifySuccess(eq("test-open-id"), eq("INV-2026-001"), any());
    }

    @Test
    void shouldNotifyFailureWhenInvoiceFailed() throws Exception {
        LarkEvent event = buildEvent("test-approval-code", "APPROVED");
        event.getEvent().setForm(buildFormJson());

        when(objectMapper.readValue(anyString(), eq(List.class))).thenReturn(buildFormList());

        InvoiceResult result = new InvoiceResult();
        result.setSuccess(false);
        result.setErrorMsg("金蝶接口错误");
        when(kingdeeInvoiceClient.createInvoice(any())).thenReturn(result);

        handler.handle(event);

        verify(larkNotifyService).notifyFailure(eq("test-open-id"), contains("金蝶接口错误"));
    }

    @Test
    void shouldSkipDuplicateInstanceCode() throws Exception {
        LarkEvent event = buildEvent("test-approval-code", "APPROVED");
        event.getEvent().setForm(buildFormJson());
        when(objectMapper.readValue(anyString(), eq(List.class))).thenReturn(buildFormList());

        InvoiceResult result = new InvoiceResult();
        result.setSuccess(true);
        result.setInvoiceNo("INV-001");
        when(kingdeeInvoiceClient.createInvoice(any())).thenReturn(result);

        handler.handle(event);
        handler.handle(event); // 第二次应跳过

        verify(kingdeeInvoiceClient, times(1)).createInvoice(any());
    }

    private LarkEvent buildEvent(String approvalCode, String status) {
        LarkEvent event = new LarkEvent();
        LarkEvent.EventBody body = new LarkEvent.EventBody();
        body.setApprovalCode(approvalCode);
        body.setInstanceCode("test-instance-001");
        body.setStatus(status);
        body.setOpenId("test-open-id");
        event.setEvent(body);
        return event;
    }

    private String buildFormJson() {
        return "[{\"id\":\"field_buyer_name\",\"value\":\"测试公司\"}]";
    }

    private List<?> buildFormList() {
        return List.of(
                java.util.Map.of("id", "field_buyer_name", "value", "测试公司"),
                java.util.Map.of("id", "field_buyer_tax_no", "value", "91110000123456789X"),
                java.util.Map.of("id", "field_buyer_address_phone", "value", "北京市 010-12345678"),
                java.util.Map.of("id", "field_buyer_bank_account", "value", "工商银行 6222001234567890"),
                java.util.Map.of("id", "field_invoice_type", "value", "增值税专用发票"),
                java.util.Map.of("id", "field_amount", "value", "10000.00"),
                java.util.Map.of("id", "field_items", "value", "[]")
        );
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -pl . -Dtest=ApprovalEventHandlerTest -q 2>&1 | tail -20
```
期望：编译错误（`ApprovalEventHandler` 尚未创建）

- [ ] **Step 3: 实现 ApprovalEventHandler**

`src/main/java/com/larkdinvoice/handler/ApprovalEventHandler.java`:
```java
package com.larkdinvoice.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkdinvoice.client.KingdeeInvoiceClient;
import com.larkdinvoice.config.AppConfig;
import com.larkdinvoice.model.*;
import com.larkdinvoice.service.LarkNotifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalEventHandler {

    private final AppConfig appConfig;
    private final KingdeeInvoiceClient kingdeeInvoiceClient;
    private final LarkNotifyService larkNotifyService;
    private final ObjectMapper objectMapper;

    // 幂等集合
    private final Set<String> processedInstances = ConcurrentHashMap.newKeySet();

    @Async("webhookExecutor")
    public void handle(LarkEvent event) {
        LarkEvent.EventBody body = event.getEvent();
        if (body == null) {
            return;
        }

        // 过滤非目标审批
        if (!appConfig.getApprovalCode().equals(body.getApprovalCode())) {
            return;
        }

        // 过滤非通过状态
        if (!"APPROVED".equals(body.getStatus())) {
            return;
        }

        // 幂等检查
        String instanceCode = body.getInstanceCode();
        if (!processedInstances.add(instanceCode)) {
            log.info("Duplicate approval instance {}, skipping", instanceCode);
            return;
        }

        try {
            ApprovalForm form = parseForm(body);
            if (form == null) {
                larkNotifyService.notifyFailure(body.getOpenId(), "审批表单字段不完整，请联系管理员");
                return;
            }

            InvoiceRequest request = buildInvoiceRequest(form, instanceCode);
            InvoiceResult result = kingdeeInvoiceClient.createInvoice(request);

            if (result.isSuccess()) {
                log.info("Invoice created successfully: {}", result.getInvoiceNo());
                larkNotifyService.notifySuccess(body.getOpenId(), result.getInvoiceNo(), form.getAmount());
            } else {
                log.error("Invoice creation failed: {}", result.getErrorMsg());
                larkNotifyService.notifyFailure(body.getOpenId(), result.getErrorMsg());
                processedInstances.remove(instanceCode); // 失败时移除，允许重试
            }
        } catch (Exception e) {
            log.error("Error handling approval event for instance {}", instanceCode, e);
            larkNotifyService.notifyFailure(body.getOpenId(), "系统异常，请联系管理员");
            processedInstances.remove(instanceCode);
        }
    }

    private ApprovalForm parseForm(LarkEvent.EventBody body) {
        try {
            List<Map<String, Object>> formList = objectMapper.readValue(
                    body.getForm(), new TypeReference<>() {});
            Map<String, String> fieldMap = formList.stream()
                    .collect(Collectors.toMap(
                            m -> (String) m.get("id"),
                            m -> String.valueOf(m.getOrDefault("value", ""))));

            AppConfig.FormFields ff = appConfig.getFormFields();
            String buyerName = fieldMap.get(ff.getBuyerName());
            String buyerTaxNo = fieldMap.get(ff.getBuyerTaxNo());
            String amount = fieldMap.get(ff.getAmount());

            if (isBlank(buyerName) || isBlank(buyerTaxNo) || isBlank(amount)) {
                log.warn("Required form fields missing for instance {}", body.getInstanceCode());
                return null;
            }

            return ApprovalForm.builder()
                    .instanceCode(body.getInstanceCode())
                    .applicantOpenId(body.getOpenId())
                    .buyerName(buyerName)
                    .buyerTaxNo(buyerTaxNo)
                    .buyerAddressPhone(fieldMap.getOrDefault(ff.getBuyerAddressPhone(), ""))
                    .buyerBankAccount(fieldMap.getOrDefault(ff.getBuyerBankAccount(), ""))
                    .invoiceType(fieldMap.getOrDefault(ff.getInvoiceType(), "普通发票"))
                    .amount(new BigDecimal(amount))
                    .items(Collections.emptyList())
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse approval form", e);
            return null;
        }
    }

    private InvoiceRequest buildInvoiceRequest(ApprovalForm form, String instanceCode) {
        return InvoiceRequest.builder()
                .requestNo(instanceCode)
                .buyerName(form.getBuyerName())
                .buyerTaxNo(form.getBuyerTaxNo())
                .buyerAddressPhone(form.getBuyerAddressPhone())
                .buyerBankAccount(form.getBuyerBankAccount())
                .invoiceType(form.getInvoiceType())
                .totalAmount(form.getAmount())
                .items(Collections.emptyList())
                .build();
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn test -pl . -Dtest=ApprovalEventHandlerTest -q 2>&1 | tail -20
```
期望：`Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/larkdinvoice/handler/ src/test/java/com/larkdinvoice/handler/
git commit -m "feat: add ApprovalEventHandler with idempotency control"
```

---

## Task 6: KingdeeInvoiceClient（金蝶发票云 API 客户端）

**Files:**
- Create: `src/main/java/com/larkdinvoice/client/KingdeeInvoiceClient.java`
- Create: `src/test/java/com/larkdinvoice/client/KingdeeInvoiceClientTest.java`

- [ ] **Step 1: 编写失败测试**

`src/test/java/com/larkdinvoice/client/KingdeeInvoiceClientTest.java`:
```java
package com.larkdinvoice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkdinvoice.config.AppConfig;
import com.larkdinvoice.model.InvoiceRequest;
import com.larkdinvoice.model.InvoiceResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class KingdeeInvoiceClientTest {

    private MockWebServer mockWebServer;
    private KingdeeInvoiceClient client;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        AppConfig.KingdeeConfig config = new AppConfig.KingdeeConfig();
        config.setApiUrl(mockWebServer.url("/").toString());
        config.setAppKey("test-app-key");
        config.setAppSecret("test-app-secret");

        client = new KingdeeInvoiceClient(config, objectMapper);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void shouldReturnSuccessWhenApiReturnsInvoiceNo() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"success\":true,\"invoiceNo\":\"INV-2026-001\"}")
                .addHeader("Content-Type", "application/json"));

        InvoiceResult result = client.createInvoice(buildRequest());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getInvoiceNo()).isEqualTo("INV-2026-001");
    }

    @Test
    void shouldReturnFailureWhenApiReturnsError() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"success\":false,\"errorCode\":\"E001\",\"errorMsg\":\"税号无效\"}")
                .addHeader("Content-Type", "application/json"));

        InvoiceResult result = client.createInvoice(buildRequest());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("税号无效");
    }

    @Test
    void shouldReturnFailureWhenApiTimesOut() {
        mockWebServer.enqueue(new MockResponse().setSocketPolicy(
                okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE));

        InvoiceResult result = client.createInvoice(buildRequest());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("timeout");
    }

    @Test
    void shouldIncludeAuthHeaderInRequest() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"success\":true,\"invoiceNo\":\"INV-001\"}")
                .addHeader("Content-Type", "application/json"));

        client.createInvoice(buildRequest());

        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getHeader("X-App-Key")).isEqualTo("test-app-key");
        assertThat(request.getHeader("X-Signature")).isNotBlank();
    }

    private InvoiceRequest buildRequest() {
        return InvoiceRequest.builder()
                .requestNo("instance-001")
                .buyerName("测试公司")
                .buyerTaxNo("91110000123456789X")
                .buyerAddressPhone("北京市 010-12345678")
                .buyerBankAccount("工商银行 6222001234567890")
                .invoiceType("增值税专用发票")
                .totalAmount(new BigDecimal("10000.00"))
                .items(Collections.emptyList())
                .build();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -pl . -Dtest=KingdeeInvoiceClientTest -q 2>&1 | tail -20
```
期望：编译错误（`KingdeeInvoiceClient` 尚未创建）

- [ ] **Step 3: 实现 KingdeeInvoiceClient**

`src/main/java/com/larkdinvoice/client/KingdeeInvoiceClient.java`:
```java
package com.larkdinvoice.client;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkdinvoice.config.AppConfig;
import com.larkdinvoice.model.InvoiceRequest;
import com.larkdinvoice.model.InvoiceResult;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class KingdeeInvoiceClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_RETRIES = 3;

    private final AppConfig.KingdeeConfig config;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    public KingdeeInvoiceClient(AppConfig.KingdeeConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public InvoiceResult createInvoice(InvoiceRequest request) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String json = objectMapper.writeValueAsString(request);
                String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
                String signature = sign(timestamp, json);

                Request httpRequest = new Request.Builder()
                        .url(config.getApiUrl() + "/invoice/create")
                        .post(RequestBody.create(json, JSON))
                        .header("X-App-Key", config.getAppKey())
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", signature)
                        .header("Content-Type", "application/json")
                        .build();

                try (Response response = httpClient.newCall(httpRequest).execute()) {
                    String body = response.body() != null ? response.body().string() : "{}";
                    return objectMapper.readValue(body, InvoiceResult.class);
                }
            } catch (java.net.SocketTimeoutException e) {
                log.warn("Kingdee API timeout on attempt {}/{}", attempt, MAX_RETRIES);
                if (attempt == MAX_RETRIES) {
                    InvoiceResult result = new InvoiceResult();
                    result.setSuccess(false);
                    result.setErrorMsg("timeout: Kingdee API did not respond in time");
                    return result;
                }
                sleepForRetry(attempt);
            } catch (Exception e) {
                log.error("Kingdee API call failed on attempt {}/{}", attempt, MAX_RETRIES, e);
                if (attempt == MAX_RETRIES) {
                    InvoiceResult result = new InvoiceResult();
                    result.setSuccess(false);
                    result.setErrorMsg("调用金蝶接口异常：" + e.getMessage());
                    return result;
                }
                sleepForRetry(attempt);
            }
        }
        InvoiceResult result = new InvoiceResult();
        result.setSuccess(false);
        result.setErrorMsg("调用金蝶接口失败，已重试" + MAX_RETRIES + "次");
        return result;
    }

    private String sign(String timestamp, String body) {
        String content = config.getAppKey() + timestamp + body + config.getAppSecret();
        return DigestUtil.sha256Hex(content);
    }

    private void sleepForRetry(int attempt) {
        try {
            Thread.sleep((long) Math.pow(2, attempt - 1) * 1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn test -pl . -Dtest=KingdeeInvoiceClientTest -q 2>&1 | tail -20
```
期望：`Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/larkdinvoice/client/ src/test/java/com/larkdinvoice/client/
git commit -m "feat: add KingdeeInvoiceClient with retry and signature"
```

---

## Task 7: LarkNotifyService（飞书消息通知）

**Files:**
- Create: `src/main/java/com/larkdinvoice/service/LarkNotifyService.java`
- Create: `src/test/java/com/larkdinvoice/service/LarkNotifyServiceTest.java`

- [ ] **Step 1: 编写失败测试**

`src/test/java/com/larkdinvoice/service/LarkNotifyServiceTest.java`:
```java
package com.larkdinvoice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkdinvoice.config.AppConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class LarkNotifyServiceTest {

    private MockWebServer mockWebServer;
    private LarkNotifyService service;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        AppConfig config = new AppConfig();
        config.setAppId("test-app-id");
        config.setAppSecret("test-app-secret");

        service = new LarkNotifyService(config, objectMapper,
                mockWebServer.url("/").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void shouldSendSuccessMessageWithInvoiceNo() throws Exception {
        // 先 mock token 接口
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"code\":0,\"tenant_access_token\":\"test-token\",\"expire\":7200}")
                .addHeader("Content-Type", "application/json"));
        // 再 mock 发消息接口
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"code\":0}")
                .addHeader("Content-Type", "application/json"));

        service.notifySuccess("ou_test123", "INV-2026-001", new BigDecimal("10000.00"));

        mockWebServer.takeRequest(); // token request
        RecordedRequest msgRequest = mockWebServer.takeRequest();
        String body = msgRequest.getBody().readUtf8();
        assertThat(body).contains("INV-2026-001");
        assertThat(body).contains("10000.00");
    }

    @Test
    void shouldSendFailureMessageWithErrorReason() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"code\":0,\"tenant_access_token\":\"test-token\",\"expire\":7200}")
                .addHeader("Content-Type", "application/json"));
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"code\":0}")
                .addHeader("Content-Type", "application/json"));

        service.notifyFailure("ou_test123", "税号格式不正确");

        mockWebServer.takeRequest();
        RecordedRequest msgRequest = mockWebServer.takeRequest();
        String body = msgRequest.getBody().readUtf8();
        assertThat(body).contains("税号格式不正确");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -pl . -Dtest=LarkNotifyServiceTest -q 2>&1 | tail -20
```
期望：编译错误（`LarkNotifyService` 尚未创建）

- [ ] **Step 3: 实现 LarkNotifyService**

`src/main/java/com/larkdinvoice/service/LarkNotifyService.java`:
```java
package com.larkdinvoice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkdinvoice.config.AppConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class LarkNotifyService {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final AppConfig appConfig;
    private final ObjectMapper objectMapper;
    private final String larkBaseUrl;
    private final OkHttpClient httpClient;

    // token 缓存
    private final AtomicReference<String> cachedToken = new AtomicReference<>();
    private final AtomicLong tokenExpireAt = new AtomicLong(0);

    public LarkNotifyService(AppConfig appConfig, ObjectMapper objectMapper,
                              String larkBaseUrl) {
        this.appConfig = appConfig;
        this.objectMapper = objectMapper;
        this.larkBaseUrl = larkBaseUrl;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    // Spring 默认构造器，使用飞书正式地址
    public LarkNotifyService(AppConfig appConfig, ObjectMapper objectMapper) {
        this(appConfig, objectMapper, "https://open.feishu.cn");
    }

    public void notifySuccess(String openId, String invoiceNo, BigDecimal amount) {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String text = String.format("✅ 开票成功\n发票号：%s\n金额：%s 元\n时间：%s",
                invoiceNo, amount.toPlainString(), time);
        sendMessage(openId, text);
    }

    public void notifyFailure(String openId, String reason) {
        String text = String.format("❌ 开票失败\n原因：%s\n请联系管理员处理", reason);
        sendMessage(openId, text);
    }

    private void sendMessage(String openId, String text) {
        try {
            String token = getAccessToken();
            Map<String, Object> payload = Map.of(
                    "receive_id", openId,
                    "msg_type", "text",
                    "content", objectMapper.writeValueAsString(Map.of("text", text))
            );

            Request request = new Request.Builder()
                    .url(larkBaseUrl + "/open-apis/im/v1/messages?receive_id_type=open_id")
                    .post(RequestBody.create(objectMapper.writeValueAsString(payload), JSON))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "{}";
                JsonNode node = objectMapper.readTree(body);
                if (node.path("code").asInt() != 0) {
                    log.error("Failed to send Lark message: {}", body);
                }
            }
        } catch (Exception e) {
            log.error("Error sending Lark notification to {}", openId, e);
        }
    }

    private String getAccessToken() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        // 提前 300 秒刷新
        if (cachedToken.get() != null && tokenExpireAt.get() - now > 300) {
            return cachedToken.get();
        }

        Map<String, String> payload = Map.of(
                "app_id", appConfig.getAppId(),
                "app_secret", appConfig.getAppSecret()
        );

        Request request = new Request.Builder()
                .url(larkBaseUrl + "/open-apis/auth/v3/tenant_access_token/internal")
                .post(RequestBody.create(objectMapper.writeValueAsString(payload), JSON))
                .header("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "{}";
            JsonNode node = objectMapper.readTree(body);
            String token = node.path("tenant_access_token").asText();
            int expire = node.path("expire").asInt(7200);
            cachedToken.set(token);
            tokenExpireAt.set(System.currentTimeMillis() / 1000 + expire);
            return token;
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn test -pl . -Dtest=LarkNotifyServiceTest -q 2>&1 | tail -20
```
期望：`Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/larkdinvoice/service/ src/test/java/com/larkdinvoice/service/
git commit -m "feat: add LarkNotifyService with token caching"
```

---

## Task 8: 全量测试 & 推送

**Files:** 无新文件

- [ ] **Step 1: 运行全量测试**

```bash
mvn test -q 2>&1 | tail -30
```
期望：所有测试通过，`BUILD SUCCESS`

- [ ] **Step 2: 打包验证**

```bash
mvn package -DskipTests -q
```
期望：`target/lark-d-invoice-1.0.0-SNAPSHOT.jar` 生成，`BUILD SUCCESS`

- [ ] **Step 3: 推送到远程**

```bash
git push -u origin main
```

---

## 部署说明

启动时需要配置以下环境变量：

```bash
LARK_APP_ID=xxx
LARK_APP_SECRET=xxx
LARK_ENCRYPT_KEY=xxx
LARK_APPROVAL_CODE=xxx
KINGDEE_API_URL=https://xxx
KINGDEE_APP_KEY=xxx
KINGDEE_APP_SECRET=xxx
```

飞书开放平台 Webhook 地址配置为：`https://your-domain/webhook/lark`
