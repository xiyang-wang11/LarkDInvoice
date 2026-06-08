# LarkDInvoice — 飞书审批自动开票服务

飞书工作台应收单审批通过后，自动调用金蝶发票云完成开票，并将开票结果通过飞书消息通知审批发起人。

## 业务流程

```
飞书审批通过（应收单审批流）
  │  Webhook 事件推送
  ▼
WebhookController（POST /webhook/lark）
  └── 验签（SHA-256）→ 异步分发
        ▼
ApprovalEventHandlerImpl
  ├── 过滤：approval_code 匹配 + status=APPROVED
  ├── 幂等检查（ConcurrentHashMap，防重复开票）
  ├── 解析飞书审批表单字段（购方名称、税号、金额等）
  ├── PendingInvoiceStore.put(billNo, openId, amount)  ← 存映射，供回调查找
  └── KingdeeInvoiceClientImpl.createInvoice()
        ├── 同步返回发票号 → LarkNotifyService.notifySuccess() 立即通知
        ├── 返回 pending  → 等待金蝶异步回调
        └── 返回失败      → LarkNotifyService.notifyFailure() 立即通知

金蝶异步回调（POST /webhook/kingdee/callback）
  └── 解析回调数据（兼容 JSON 数组 / base64 字符串两种格式）
        ├── 开票成功 → PendingInvoiceStore.get(billNo) → notifySuccess()
        └── 开票失败 → PendingInvoiceStore.get(billNo) → notifyFailure()
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
│   ├── AppConfig.java          # 飞书配置（@ConfigurationProperties prefix=lark）
│   │   └── KingdeeConfig       # 金蝶配置（@ConfigurationProperties prefix=kingdee）
│   ├── AsyncConfig.java        # 异步线程池 webhookExecutor（4~8线程）
│   └── BeanConfig.java         # 手动装配 KingdeeAuthService / KingdeeInvoiceClient / LarkNotifyService
├── controller/
│   ├── WebhookController.java       # POST /webhook/lark（飞书事件入口）
│   └── KingdeeCallbackController.java  # POST /webhook/kingdee/callback（金蝶回调）
├── handler/
│   ├── ApprovalEventHandler.java    # 接口
│   └── ApprovalEventHandlerImpl.java # 审批事件处理，含幂等控制
├── client/
│   ├── KingdeeInvoiceClient.java    # 接口
│   └── KingdeeInvoiceClientImpl.java # 金蝶开票 API 调用（Base64编码 + accessToken + 重试）
├── service/
│   ├── KingdeeAuthService.java      # 接口
│   ├── KingdeeAuthServiceImpl.java  # 三步鉴权：getAppToken → login → accessToken（55分钟缓存）
│   ├── LarkNotifyService.java       # 接口
│   └── LarkNotifyServiceImpl.java   # 飞书机器人消息发送（tenant_access_token 缓存续期）
├── store/
│   └── PendingInvoiceStore.java     # billNo → (openId, amount) 内存映射，供异步回调查找
├── model/
│   ├── LarkEvent.java          # 飞书 Webhook 事件结构（支持 2.0 格式）
│   ├── ApprovalForm.java       # 解析后的审批表单字段
│   ├── InvoiceRequest.java     # 开票请求（含 items）
│   └── InvoiceResult.java      # 开票结果（success / pending / error）
└── dto/
    ├── KingdeeCallbackRequest.java   # 金蝶回调请求（decodeData() 兼容两种格式）
    ├── KingdeeCallbackResponse.java  # 金蝶回调响应（必须返回 success:true）
    └── kingdee/
        ├── AppTokenRequest/Response  # 第一步鉴权
        ├── LoginRequest/Response     # 第二步鉴权
        └── OpenInvoiceRequest/Response # 开票接口
```

## 接口说明

### 飞书 Webhook 入口

**POST** `/webhook/lark`

飞书开放平台事件订阅地址，处理两种请求：
- URL 验证握手：返回 `{"challenge": "..."}` 
- 审批事件：验签后异步处理，立即返回 200（飞书要求 3 秒内响应）

验签算法：`SHA-256(timestamp + nonce + encryptKey + body)`

### 金蝶回调接口

**POST** `/webhook/kingdee/callback`

由金蝶发票云主动调用，无论内部处理是否异常，必须返回：
```json
{"success": true, "code": "0", "message": "success"}
```
否则金蝶会无限重试。

判断开票成功的依据：`invoiceCode` 或 `invoiceNum` 任一不为空（全电发票无 invoiceCode，仅有 invoiceNum）。

## 金蝶鉴权流程（三步）

```
1. POST getAppToken  → appToken（用 appId + appSecret + accountId）
2. POST login        → accessToken（用 appToken + 手机号，loginType=mobile）
3. POST openInvoice  → header 携带 accessToken，data 字段为 Base64 编码的 JSON 数组
```

Token 缓存 55 分钟，过期自动刷新。开票接口收到 401 时自动刷新 token 并重试一次。

## 关键设计说明

1. **异步处理**：飞书 Webhook 收到事件后立即返回 200，业务逻辑在异步线程池处理，避免超时。

2. **幂等控制**：用 `ConcurrentHashMap` 存已处理的 `instanceCode`，防止飞书重试导致重复开票。开票失败时移除，允许重试。

3. **异步开票映射**：金蝶开票为异步流程，`PendingInvoiceStore` 在开票前存入 `billNo → (openId, amount)`，回调时查找并通知飞书。服务重启后映射丢失，但发票本身仍正常开具（可人工查询后补通知）。

4. **回调兼容性**：金蝶回调 `data` 字段存在两种格式——JSON 数组对象或 base64 编码字符串，`KingdeeCallbackRequest.decodeData()` 统一兼容处理。

5. **Token 并发安全**：`KingdeeAuthServiceImpl` 用 `ReentrantLock` 双重检查，防止并发时重复获取 token。

## 配置说明

主配置：`src/main/resources/application.yml`（默认端口 8080）

生产环境配置：`/opt/lark-d-invoice/application.yml`（覆盖 jar 内配置）

### 飞书配置（prefix: lark）

| 配置项 | 说明 |
|--------|------|
| `app-id` | 飞书应用 App ID |
| `app-secret` | 飞书应用 App Secret |
| `encrypt-key` | Webhook 验签密钥（飞书加密策略里配置） |
| `approval-code` | 目标应收单审批定义 ID（definitionCode） |
| `form-fields.*` | 审批表单字段 key 映射（需与飞书审批表单实际字段 key 对应） |

### 金蝶配置（prefix: kingdee）

| 配置项 | 演示环境值 |
|--------|-----------|
| `get-app-token-url` | `https://cosmic-demo.piaozone.com/demo/api/getAppToken.do` |
| `login-url` | `https://cosmic-demo.piaozone.com/demo/api/login.do` |
| `open-invoice-url` | `https://cosmic-demo.piaozone.com/demo/kapi/app/sim/openApi` |
| `app-id` | `CQJC` |
| `app-secret` | `Z!GEm)[O&_%{H8` |
| `account-id` | `1640533801123708928` |
| `user` | `13714962604`（登录手机号） |
| `business-system-code` | `CQJC` |
| `token-expire-minutes` | `55` |

## 生产环境部署信息

| 项目 | 值 |
|------|-----|
| 服务器公网 IP | `124.221.93.209` |
| 应用端口 | `8081`（8080 被 Invoice Assistant 占用） |
| 飞书 Webhook 地址 | `http://124.221.93.209:8081/webhook/lark` |
| 金蝶回调地址 | `http://124.221.93.209:8081/webhook/kingdee/callback` |
| jar 路径 | `/opt/lark-d-invoice/lark-d-invoice-1.0.0-SNAPSHOT.jar` |
| 配置文件 | `/opt/lark-d-invoice/application.yml` |
| 日志文件 | `/opt/lark-d-invoice/app.log` |

### 部署步骤

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

**第四步：验证启动**
```bash
plink -ssh -pw "WXY520@mm" -batch ^
  -hostkey "ssh-ed25519 255 SHA256:n39j3TV8smoSOX97f/D133FMPUf/pG0z4ZOS8vhqsew" ^
  root@124.221.93.209 "tail -20 /opt/lark-d-invoice/app.log"
```
看到 `Tomcat started on port(s): 8081` 即为成功。

### 服务器上直接操作

```bash
# 查看实时日志
tail -f /opt/lark-d-invoice/app.log

# 查看进程
ps aux | grep lark-d-invoice | grep -v grep

# 停止服务
kill -9 $(ps aux | grep lark-d-invoice | grep -v grep | awk '{print $2}')

# 启动服务
nohup java -jar /opt/lark-d-invoice/lark-d-invoice-1.0.0-SNAPSHOT.jar \
  --spring.config.location=/opt/lark-d-invoice/application.yml \
  > /opt/lark-d-invoice/app.log 2>&1 &
```

## 飞书开放平台配置

| 项目 | 值 |
|------|-----|
| App ID | `cli_aaa964a92df9dbed` |
| 审批定义 ID | `972E5FF4-CD72-4D4A-8D5D-4ABB3302EF46` |
| 事件订阅地址 | `http://124.221.93.209:8081/webhook/lark` |
| 订阅事件 | `审批实例状态变更`（approval_instance） |
| 加密策略 Encrypt Key | 已配置（见服务器 application.yml） |

## 本地开发

```bash
# 编译
mvn compile

# 运行测试
mvn test

# 打包
mvn clean package -DskipTests

# 启动（使用内置配置，端口 8080）
java -jar target/lark-d-invoice-1.0.0-SNAPSHOT.jar
```

测试覆盖：16 个测试用例，覆盖 WebhookController、ApprovalEventHandler、KingdeeInvoiceClient、LarkNotifyService。

## 待完善事项

- [ ] 审批表单字段 key 需根据实际飞书审批表单配置更新（`application.yml` 中 `form-fields.*`）
- [ ] `PendingInvoiceStore` 目前为内存实现，服务重启后丢失，生产环境可改为 Redis 持久化
- [ ] 金蝶生产环境 API 地址和凭证需替换（当前为演示环境）
- [ ] 金蝶回调地址需在金蝶后台配置：`http://124.221.93.209:8081/webhook/kingdee/callback`
