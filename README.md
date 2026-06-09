# LarkDInvoice — 飞书审批自动开票服务

飞书工作台「开票申请单」审批通过后，自动调用金蝶发票云完成开票，并将开票结果（含发票号、金额、PDF链接）通过飞书消息通知审批发起人。

**✅ 已完整跑通：飞书审批 → 金蝶开票 → 飞书通知（含PDF）**

## 业务流程

```
飞书审批通过（开票申请单-王西阳测试）
  │  Webhook 事件推送（AES-256-CBC 加密）
  ▼
WebhookController（POST /webhook/lark）
  └── 解密 → 验签 → 异步分发
        ▼
ApprovalEventHandlerImpl
  ├── 过滤：approval_code 匹配 + status=APPROVED
  ├── 幂等检查（ConcurrentHashMap）
  ├── 调飞书 API 获取审批实例详情（含表单数据）
  ├── 解析表单字段（购方、销方、金额、明细+税收分类编码）
  ├── PendingInvoiceStore.put(instanceCode, openId, amount)
  └── KingdeeInvoiceClientImpl.createInvoice()
        ├── 鉴权：getAppToken → login → accessToken（55分钟缓存）
        ├── 构建开票请求（Base64编码，lineProperty=2，revenueCode）
        ├── 同步返回发票号 → notifySuccess() 立即通知
        ├── 返回 pending   → 等待金蝶异步回调（约2分钟）
        └── 返回失败       → notifyFailure() 立即通知

金蝶异步回调（POST /webhook/kingdee/callback）
  └── base64解码 data（单个JSON对象格式）
        ├── PendingInvoiceStore.get(billNo) → 查找 openId
        ├── 开票成功 → notifySuccess(openId, invoiceNo, amount, pdfUrl)
        └── 开票失败 → notifyFailure(openId, failReason)
```

## 技术栈

- Java 11 + Spring Boot 2.7.18
- OkHttp3 4.12.0（调用飞书 / 金蝶 HTTP API）
- Hutool-crypto 5.8.26（飞书 Webhook 验签 SHA-256 + AES-256-CBC 解密）
- Lombok + Jackson
- JUnit 5 + Mockito + OkHttp MockWebServer

## 项目结构

```
src/main/java/com/larkdinvoice/
├── config/
│   ├── AppConfig.java          # 飞书配置（prefix=lark）+ 金蝶配置（KingdeeConfig, prefix=kingdee）
│   ├── AsyncConfig.java        # 异步线程池 webhookExecutor（4~8线程）
│   └── BeanConfig.java         # 手动装配三个需要非默认构造器的 Bean
├── controller/
│   ├── WebhookController.java       # POST /webhook/lark（AES解密+验签+异步分发）
│   └── KingdeeCallbackController.java  # POST /webhook/kingdee/callback（base64解码+通知）
├── handler/
│   ├── ApprovalEventHandler.java    # 接口
│   └── ApprovalEventHandlerImpl.java # 审批事件处理（API获取表单+幂等+开票）
├── client/
│   ├── KingdeeInvoiceClient.java    # 接口
│   └── KingdeeInvoiceClientImpl.java # 金蝶开票（Base64+accessToken+重试+revenueCode）
├── service/
│   ├── KingdeeAuthService.java      # 接口
│   ├── KingdeeAuthServiceImpl.java  # 三步鉴权（55分钟缓存，ReentrantLock双重检查）
│   ├── LarkNotifyService.java       # 接口（含 getTenantAccessToken、PDF重载）
│   └── LarkNotifyServiceImpl.java   # 飞书消息发送（亲切文案+PDF链接）
├── store/
│   └── PendingInvoiceStore.java     # billNo→(openId,amount) 内存映射，供异步回调查找
├── model/
│   ├── LarkEvent.java          # 飞书 Webhook 事件结构
│   ├── ApprovalForm.java       # 审批表单字段（含 sellerName、revenueCode）
│   ├── InvoiceRequest.java     # 开票请求（含 sellerName/sellerTaxpayerId/revenueCode）
│   └── InvoiceResult.java      # 开票结果（success/pending/error）
└── dto/
    ├── KingdeeCallbackRequest.java   # 金蝶回调（decodeData兼容JSON对象/数组/base64）
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
| 销方企业税号 | `widget17809205865030001` | → 仅记录，实际用配置里的 sellerTaxpayerId |
| 购方企业名称 | `widget17809262403630001` | → buyerName（优先） |
| 购方企业税号 | `widget17809258471820001` | → buyerTaxpayerId |
| 申请明细 | `widget17809206545230001` | → billDetail（fieldList，自动匹配 revenueCode） |
| 邮箱地址 | `widget17809210496760001` | → 备用 |
| 地址 | `widget17809211297910001` | → buyerAddressAndTel |

**发票类型映射（中文→金蝶代码）：**
- 增值税普通发票 → `10xdp`
- 增值税专用发票 → `10xpp`
- 增值税电子专用发票 → `10xzp`

**税收分类编码：**
- 旅游服务费 → `3070301000000000000`
- 其他 → `3070301000000000000`（默认，可在 `resolveRevenueCode()` 中扩展）

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
| 订阅事件 | `approval_instance`（审批实例状态变更）|
| 已开通权限 | 访问审批应用、查看/创建/更新/删除审批、以应用身份发送消息 |
| 审批订阅 | 已通过 API 订阅（`POST /open-apis/approval/v4/approvals/{code}/subscribe`） |

## 金蝶发票云配置（演示环境）

| 配置项 | 值 |
|--------|-----|
| getAppToken | `https://cosmic-demo.piaozone.com/demo/api/getAppToken.do` |
| login | `https://cosmic-demo.piaozone.com/demo/api/login.do` |
| 开票接口 | `https://cosmic-demo.piaozone.com/demo/kapi/app/sim/openApi` |
| appId | `CQJC1` |
| appSecret | `WXY520@mmnnbb@@@***` |
| accountId | `1640533801123708928` |
| 登录手机号 | `13714962604` |
| businessSystemCode | `CQJC1` |
| sellerTaxpayerId | `915003006188392540` |
| 回调地址（金蝶后台配置） | `http://124.221.93.209:8081/webhook/kingdee/callback` |
| 数据加密策略 | BASE64（data 字段为 base64 编码的单个 JSON 对象） |

## 关键设计说明

1. **飞书加密 Webhook**：请求体为 `{"encrypt":"..."}` 格式，用 AES-256-CBC 解密（key = SHA-256(encryptKey) 前32字节，base64解码后前16字节为IV）。

2. **表单数据获取**：`approval_instance` 事件不含表单数据，通过 `GET /open-apis/approval/v4/instances/{instanceCode}` 获取。

3. **金蝶异步回调**：开票受理后约2分钟回调，`PendingInvoiceStore` 暂存 `instanceCode→(openId,amount)`，回调时查找并通知飞书。服务重启后映射丢失，发票仍正常开具。

4. **回调 data 格式**：金蝶回调 data 字段为 base64 编码的**单个 JSON 对象**（非数组），`decodeData()` 已兼容处理。

5. **appSecret 特殊字符**：YAML 配置需单引号包裹，避免特殊字符解析错误。

6. **税收分类编码**：旅游服务费固定传 `3070301000000000000`，在 `resolveRevenueCode()` 中扩展。

7. **飞书审批订阅**：需调用飞书 API 订阅审批定义，否则审批事件不会推送。命令：
   ```bash
   curl -X POST "https://open.feishu.cn/open-apis/approval/v4/approvals/972E5FF4-CD72-4D4A-8D5D-4ABB3302EF46/subscribe" \
     -H "Authorization: Bearer {tenant_access_token}"
   ```

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

### 服务器日常操作

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

## 本地开发

```bash
# 编译
mvn compile

# 运行测试（16个用例）
mvn test

# 打包
mvn clean package -DskipTests
```

## 后续优化建议

- **PendingInvoiceStore 持久化**：当前内存实现，服务重启后丢失。可改为 Redis，确保重启后回调仍能找到 openId。
- **切换正式金蝶环境**：当前演示环境，上生产前替换 URL 和凭证。
- **税收分类编码扩展**：在 `resolveRevenueCode()` 中按商品名称补充更多映射。
- **飞书消息卡片化**：当前为文本消息，可升级为飞书消息卡片（Card），展示更丰富的发票信息。
