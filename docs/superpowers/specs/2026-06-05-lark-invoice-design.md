# 飞书审批通过自动触发金蝶发票云开票 — 设计文档

**日期：** 2026-06-05  
**项目：** LarkDInvoice  
**技术栈：** Spring Boot 3.x + 飞书开放平台 + 金蝶发票云 API

---

## 一、背景与目标

在飞书工作台中，财务人员提交应收单审批流。审批通过后，需自动调用金蝶发票云 API 完成开票，并将开票结果（发票号或失败原因）通过飞书消息通知审批发起人，减少人工介入。

---

## 二、整体架构

```
飞书开放平台
    │  审批通过事件（HTTP POST Webhook）
    ▼
Spring Boot 服务（LarkDInvoice）
    ├── WebhookController     接收并验签飞书事件，立即返回 200
    ├── ApprovalEventHandler  异步处理：过滤目标审批+通过状态，提取表单字段
    ├── KingdeeInvoiceClient  组装开票请求，调用金蝶发票云 API
    └── LarkNotifyService     回写结果到飞书（机器人消息通知发起人）
```

**数据流：**
```
飞书推送 → 验签 → 判断 approval_code 匹配 & 状态=APPROVED
    → 幂等检查（instance_code）
    → 提取表单字段 → 调用金蝶开票 API
    → 成功：飞书通知发起人（含发票号）
    → 失败：飞书通知失败原因，记录日志
```

---

## 三、核心组件设计

### 3.1 WebhookController

- 路由：`POST /webhook/lark`
- 处理飞书 URL 验证握手（返回 `challenge` 字段）
- 用 `encrypt_key` 对请求体验签（HMAC-SHA256），验签失败返回 403
- 验签通过后，将事件投入异步线程处理，立即返回 HTTP 200
- 飞书要求 3 秒内响应，异步处理避免超时

### 3.2 ApprovalEventHandler

- 监听事件类型：`approval_instance`
- 过滤条件：`approval_code` 与配置匹配 且 `status == APPROVED`
- 幂等控制：用 `instance_code` 作为幂等键，已处理过的直接跳过
- 从 `form_contents` 按配置的字段 key 提取开票所需信息：
  - 购方名称、购方税号、购方地址电话、购方银行账号
  - 销售方信息（若表单有）、发票类型、含税金额、开票明细（货物名称/规格/数量/单价/税率）
- 必填字段校验，缺失时飞书通知发起人

### 3.3 KingdeeInvoiceClient

- 封装金蝶发票云 HTTP API 调用
- 认证方式：AppKey + AppSecret 签名（具体签名算法按金蝶文档实现）
- 请求超时：连接 5s，读取 30s
- 失败重试：最多 3 次，指数退避（1s、2s、4s）
- 返回值：发票号（成功）或错误信息（失败）

### 3.4 LarkNotifyService

- 使用飞书机器人 `im.v1.messages.create` API 发送消息
- 开票成功：发送给审批发起人，内容包含发票号、开票金额、时间
- 开票失败：发送给审批发起人，内容包含失败原因，提示联系管理员
- access_token 自动续期（提前 5 分钟刷新）

---

## 四、异常处理策略

| 场景 | 处理方式 |
|------|----------|
| 验签失败 | 返回 403，不处理，记录警告日志 |
| 非目标审批或非通过状态 | 忽略，返回 200 |
| 表单必填字段缺失 | 飞书通知发起人"字段不完整"，记录日志 |
| 金蝶 API 调用失败（重试后仍失败） | 飞书通知发起人失败原因，记录 ERROR 日志 |
| 重复事件（飞书重试） | 幂等检查跳过，返回 200 |
| 全局未知异常 | 记录日志，返回 200（避免飞书无限重试） |

**幂等实现：**  
使用内存 `ConcurrentHashMap<String, Boolean>` 存储已处理的 `instance_code`。重启后依赖金蝶侧的幂等兜底（相同申请单号拒绝重复开票）。

---

## 五、项目结构

```
src/main/java/com/larkdinvoice/
├── controller/
│   └── WebhookController.java
├── handler/
│   └── ApprovalEventHandler.java
├── client/
│   └── KingdeeInvoiceClient.java
├── service/
│   └── LarkNotifyService.java
├── model/
│   ├── LarkEvent.java          飞书事件结构
│   ├── ApprovalForm.java       解析后的审批表单字段
│   └── InvoiceRequest.java     金蝶开票请求结构
└── config/
    └── AppConfig.java          配置属性绑定
```

---

## 六、配置项

```yaml
lark:
  app-id: ${LARK_APP_ID}
  app-secret: ${LARK_APP_SECRET}
  encrypt-key: ${LARK_ENCRYPT_KEY}       # Webhook 验签密钥
  approval-code: ${LARK_APPROVAL_CODE}   # 目标应收单审批定义ID
  form-fields:                            # 审批表单字段 key 映射
    buyer-name: field_xxx
    buyer-tax-no: field_xxx
    amount: field_xxx
    items: field_xxx

kingdee:
  api-url: ${KINGDEE_API_URL}
  app-key: ${KINGDEE_APP_KEY}
  app-secret: ${KINGDEE_APP_SECRET}
```

敏感配置通过环境变量注入，不硬编码。

---

## 七、技术依赖

| 依赖 | 用途 |
|------|------|
| Spring Boot 3.x | 主框架 |
| Spring Web | Webhook 接口 |
| Spring Async | 异步处理审批事件 |
| OkHttp 或 Spring WebClient | 调用飞书/金蝶外部 API |
| Jackson | JSON 序列化 |
| Lombok | 减少样板代码 |
| Hutool-crypto | HMAC-SHA256 验签 |

---

## 八、不在本期范围内

- 持久化已处理记录到数据库（当前用内存 Map）
- 管理后台 / 开票记录查询
- 多审批流类型支持
- 金蝶开票状态异步回调（当前为同步调用）
