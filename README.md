# LarkDInvoice — 飞书审批自动开票服务

飞书工作台「开票申请单」审批通过后，自动调用金蝶发票云完成开票，并将开票结果通过飞书消息通知审批发起人。

## 业务流程

```
飞书审批通过（开票申请单-王西阳测试）
  │  Webhook 事件推送（加密）
  ▼
WebhookController（POST /webhook/lark）
  └── AES-256-CBC 解密 → 验签 → 异步分发
        ▼
ApprovalEventHandlerImpl
  ├── 过滤：approval_code 匹配 + status=APPROVED
  ├── 幂等检查（ConcurrentHashMap，防重复开票）
  ├── 调飞书 API 获取审批实例详情（含表单数据）
  ├── 解析表单字段（购方名称、税号、金额、明细等）
  ├── PendingInvoiceStore.put(instanceCode, openId, amount)
  └── KingdeeInvoiceClientImpl.createInvoice()
        ├── 鉴权：getAppToken → login → accessToken（55分钟缓存）
        ├── 构建开票请求（Base64编码 data 字段）
        ├── 同步返回发票号 → LarkNotifyService.notifySuccess()
        ├── 返回 pending   → 等待金蝶异步回调
        └── 返回失败       → LarkNotifyService.notifyFailure()

金蝶异步回调（POST /webhook/kingdee/callback）
  └── 解析回调数据 → PendingInvoiceStore.get(billNo)
        ├── 开票成功 → notifySuccess(openId, invoiceNo, amount)
        └── 开票失败 → notifyFailure(openId, failReason)
```

## 技术栈

- Java 11 + Spring Boot 2.7.18
- OkHttp3 4.12.0（调用飞书 / 金蝶 HTTP API）
- Hutool-crypto 5.8.26（飞书 Webhook 验签 SHA-256）
- Lombok + Jackson
- JUnit 5 + Mockito + OkHttp MockWebServer

## 项目结构

```
src/main/java/com/larkdinvoice/
├── config/
│   ├── AppConfig.java          # 飞书配置（prefix=lark）+ 金蝶配置（KingdeeConfig, prefix=kingdee）
│   ├── AsyncConfig.java        # 异步线程池 webhookExecutor（4~8线程）
│   └── BeanConfig.java         # 手动装配 KingdeeAuthService / KingdeeInvoiceClient / LarkNotifyService
├── controller/
│   ├── WebhookController.java       # POST /webhook/lark（飞书事件入口，含AES解密）
│   └── KingdeeCallbackController.java  # POST /webhook/kingdee/callback（金蝶回调）
├── handler/
│   ├── ApprovalEventHandler.java    # 接口
│   └── ApprovalEventHandlerImpl.java # 审批事件处理（通过API获取表单+幂等控制）
├── client/
│   ├── KingdeeInvoiceClient.java    # 接口
│   └── KingdeeInvoiceClientImpl.java # 金蝶开票（Base64+accessToken+重试，lineProperty=2）
├── service/
│   ├── KingdeeAuthService.java      # 接口
│   ├── KingdeeAuthServiceImpl.java  # 三步鉴权（getAppToken→login→accessToken）
│   ├── LarkNotifyService.java       # 接口（含 getTenantAccessToken）
│   └── LarkNotifyServiceImpl.java   # 飞书消息发送（token缓存，提前5分钟续期）
├── store/
│   └── PendingInvoiceStore.java     # billNo→(openId,amount) 内存映射，供异步回调查找
├── model/
│   ├── LarkEvent.java          # 飞书 Webhook 事件结构
│   ├── ApprovalForm.java       # 审批表单字段（含 sellerName、items）
│   ├── InvoiceRequest.java     # 开票请求（含 sellerName/sellerTaxpayerId）
│   └── InvoiceResult.java      # 开票结果（success/pending/error）
└── dto/
    ├── KingdeeCallbackRequest.java   # 金蝶回调（decodeData兼容JSON/base64两种格式）
    ├── KingdeeCallbackResponse.java  # 金蝶回调响应（必须返回success:true）
    └── kingdee/
        ├── AppTokenRequest/Response  # 第一步鉴权
        ├── LoginRequest/Response     # 第二步鉴权
        └── OpenInvoiceRequest/Response # 开票接口
```

## 飞书审批表单字段映射

审批定义：**开票申请单-王西阳测试**（definitionCode: `972E5FF4-CD72-4D4A-8D5D-4ABB3302EF46`）

| 字段名 | Widget ID | 用途 |
|--------|-----------|------|
| 销方企业名称 | `widget17809198123880001` | → sellerName |
| 开票类型 | `widget17809203741940001` | → invoiceType（中文→金蝶代码） |
| 申请金额 | `widget17809204063310001` | → totalAmount |
| 客户/开票名称 | `widget17809205685890001` | → buyerName（fallback） |
| 销方企业税号 | `widget17809205865030001` | → sellerTaxpayerId（表单值，配置覆盖） |
| 购方企业名称 | `widget17809262403630001` | → buyerName（优先） |
| 购方企业税号 | `widget17809258471820001` | → buyerTaxpayerId |
| 申请明细 | `widget17809206545230001` | → billDetail（fieldList类型） |
| 邮箱地址 | `widget17809210496760001` | → buyerRecipientMail（备用） |
| 地址 | `widget17809211297910001` | → buyerAddressAndTel |

**发票类型映射**（中文→金蝶代码）：
- 增值税普通发票 → `10xdp`
- 增值税专用发票 → `10xpp`
- 增值税电子专用发票 → `10xzp`

## 生产环境部署信息

| 项目 | 值 |
|------|-----|
| 服务器 | `124.221.93.209`（腾讯云轻量服务器） |
| 应用端口 | `8081`（8080 被 Invoice Assistant 占用） |
| 飞书 Webhook 地址 | `http://124.221.93.209:8081/webhook/lark` |
| 金蝶回调地址 | `http://124.221.93.209:8081/webhook/kingdee/callback` |
| jar 路径 | `/opt/lark-d-invoice/lark-d-invoice-1.0.0-SNAPSHOT.jar` |
| 配置文件 | `/opt/lark-d-invoice/application.yml` |
| 日志文件 | `/opt/lark-d-invoice/app.log` |
| SSH | `root@124.221.93.209`，密码 `WXY520@mm` |

## 飞书开放平台配置

| 项目 | 值 |
|------|-----|
| App ID | `cli_aaa964a92df9dbed` |
| 审批定义 ID | `972E5FF4-CD72-4D4A-8D5D-4ABB3302EF46` |
| Encrypt Key | `9rz4GBkOp3pFeGhutPvaueM7XudyWKlw` |
| 事件订阅地址 | `http://124.221.93.209:8081/webhook/lark` |
| 已订阅事件 | `approval_instance`（审批实例状态变更）|
| 已开通权限 | 访问审批应用、查看/创建/更新/删除审批、以应用身份发送消息 |

> **注意**：飞书消息发送权限（`im:message:send_as_bot`）刚开通，如发消息仍失败需确认版本已发布。

## 金蝶发票云配置（演示环境）

| 配置项 | 值 |
|--------|-----|
| getAppToken 地址 | `https://cosmic-demo.piaozone.com/demo/api/getAppToken.do` |
| login 地址 | `https://cosmic-demo.piaozone.com/demo/api/login.do` |
| 开票接口地址 | `https://cosmic-demo.piaozone.com/demo/kapi/app/sim/openApi` |
| appId | `CQJC` |
| appSecret | `Z!GEm)[O&amp;_%{H8`（注意：含 `&amp;` 字符串，非 HTML 实体） |
| accountId | `1640533801123708928` |
| 登录手机号 | `13714962604` |
| businessSystemCode | `CQJC` |
| sellerTaxpayerId | `915003006188392540` |

## 关键设计说明

1. **加密 Webhook**：飞书开启加密策略后请求体为 `{"encrypt":"..."}` 格式，服务用 AES-256-CBC 解密（key = SHA-256(encryptKey) 前32字节，base64解码后前16字节为IV）。

2. **表单数据获取**：`approval_instance` 事件不含表单数据，需通过飞书审批实例详情 API（`GET /open-apis/approval/v4/instances/{instanceCode}`）获取。

3. **异步开票映射**：金蝶开票为异步流程，`PendingInvoiceStore` 在开票前存入 `instanceCode → (openId, amount)`，回调时查找并通知飞书。服务重启后映射丢失，但发票仍正常开具。

4. **幂等控制**：用 `ConcurrentHashMap` 存已处理的 `instanceCode`，开票失败时移除允许重试。

5. **appSecret 特殊字符**：金蝶 appSecret 包含 `&amp;` 字符串，YAML 配置时需用单引号包裹：`'Z!GEm)[O&amp;_%{H8'`。

6. **明细行性质**：金蝶要求 `lineProperty` 必须指定，当前统一设为 `2`。

## 部署步骤

**第一步：本地打包**
```bash
mvn clean package -DskipTests
```

**第二步：上传 jar（Windows + PuTTY）**
```bash
pscp -pw "WXY520@mm" -hostkey "ssh-ed25519 255 SHA256:n39j3TV8smoSOX97f/D133FMPUf/pG0z4ZOS8vhqsew" ^
  target\lark-d-invoice-1.0.0-SNAPSHOT.jar ^
  root@124.221.93.209:/opt/lark-d-invoice/lark-d-invoice-1.0.0-SNAPSHOT.jar
```

**第三步：重启服务**
```bash
plink -ssh -pw "WXY520@mm" -batch ^
  -hostkey "ssh-ed25519 255 SHA256:n39j3TV8smoSOX97f/D133FMPUf/pG0z4ZOS8vhqsew" ^
  root@124.221.93.209 ^
  "kill -9 $(ps aux | grep lark-d-invoice | grep -v grep | awk '{print $2}') 2>/dev/null; sleep 2; nohup java -jar /opt/lark-d-invoice/lark-d-invoice-1.0.0-SNAPSHOT.jar --spring.config.location=/opt/lark-d-invoice/application.yml > /opt/lark-d-invoice/app.log 2>&1 &"
```

**第四步：验证**
```bash
plink -ssh -pw "WXY520@mm" -batch ^
  -hostkey "ssh-ed25519 255 SHA256:n39j3TV8smoSOX97f/D133FMPUf/pG0z4ZOS8vhqsew" ^
  root@124.221.93.209 "tail -20 /opt/lark-d-invoice/app.log"
```

### 服务器上直接操作

```bash
# 实时日志
tail -f /opt/lark-d-invoice/app.log

# 查看进程
ps aux | grep lark-d-invoice | grep -v grep

# 停止
kill -9 $(ps aux | grep lark-d-invoice | grep -v grep | awk '{print $2}')

# 启动
nohup java -jar /opt/lark-d-invoice/lark-d-invoice-1.0.0-SNAPSHOT.jar \
  --spring.config.location=/opt/lark-d-invoice/application.yml \
  > /opt/lark-d-invoice/app.log 2>&1 &
```

## 待办事项（明天继续）

### 🔴 高优先级

- [ ] **验证完整开票流程是否跑通**
  - 重新发起一条审批并审批通过
  - 观察日志：金蝶是否返回 `status:true` 或受理成功
  - 如金蝶返回新错误，按错误信息继续调整

- [ ] **验证飞书消息通知是否正常**
  - 飞书消息权限（`im:message:send_as_bot`）刚开通，需确认版本已发布
  - 检查审批通过后飞书是否收到开票成功/失败的消息通知

- [ ] **金蝶回调地址配置**
  - 在金蝶发票云后台配置回调地址：`http://124.221.93.209:8081/webhook/kingdee/callback`
  - 这样异步开票完成后金蝶才会推送结果

### 🟡 中优先级

- [ ] **审批表单字段 key 确认**
  - `widget17809205865030001` 字段名已从「税务登记证号」改为「销方企业税号」
  - 当前代码用表单里的该字段值，但配置里 `seller-taxpayer-id` 会覆盖
  - 确认是否需要从表单取销方税号，还是固定在配置里

- [ ] **更新本地 application.yml**
  - 本地 `src/main/resources/application.yml` 仍是旧配置
  - 应与服务器 `/opt/lark-d-invoice/application.yml` 保持一致（不含敏感凭证）

- [ ] **提交所有未提交的代码改动**
  - 最近的改动（明细解析、lineProperty、sellerName等）尚未 git commit/push

### 🟢 低优先级（后续优化）

- [ ] **PendingInvoiceStore 持久化**
  - 当前内存实现，服务重启后丢失
  - 可改为 Redis 或数据库，防止重启后回调找不到 openId

- [ ] **切换正式金蝶环境**
  - 当前使用演示环境（cosmic-demo.piaozone.com）
  - 上生产前需替换为正式环境 URL 和凭证

- [ ] **税收分类编码（revenueCode）**
  - 当前明细未传 revenueCode，金蝶演示环境可能不强制要求
  - 正式环境可能需要，审批表单可增加该字段

- [ ] **发票 PDF 通知**
  - 当前开票成功只通知发票号
  - 可扩展为在回调收到 `invoicePdfFileUrl` 后，消息里附上 PDF 链接

## 当前已知问题

| 问题 | 状态 | 说明 |
|------|------|------|
| 金蝶演示环境开票 lineProperty=2 | 待验证 | 最新部署已修复，等待测试 |
| 飞书发消息权限 | 待确认 | im:message:send_as_bot 刚开通，需确认版本发布 |
| 金蝶回调未配置 | 待处理 | 需在金蝶后台配置回调地址 |
