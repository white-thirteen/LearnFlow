# 百炼 APP 模式配置与验收

在 Nacos 的 `aigc-service.yaml` 中配置（合并到已有 `tj.ai` 下，不要重复声明 YAML 根节点）：

```yaml
tj:
  ai:
    chat-type: APP
    dashscope:
      key: ${DASHSCOPE_API_KEY}
      app-agent:
        id: ${DASHSCOPE_APP_ID}
        tools: []
        # 使用用户级鉴权插件时，改成百炼控制台的插件 ID：
        # tools:
        #   - your_plugin_id
```

环境变量需要在 aigc-service 进程中设置；也可由现有配置管理方式注入实际值。
`tools` 可以省略，没有插件时默认空列表。这里填百炼插件 ID，不是 Java 工具方法名。
插件需在百炼中配置用户级鉴权，并将 `user_token` 配置为业务接口需要的鉴权头；
其值来自用户请求的登录 Token，与调用百炼的 API Key 不同。
该实现使用 SDK 默认的中国内地端点，API Key 和应用必须属于该端点对应的地域与业务空间。

重启 gateway 和 aigc-service，并部署更新后的 auth-resource-sdk。
通过网关调用 `/ais/session` 创建会话，然后携带登录 `Authorization` 调用 `/ais/chat`。
直接访问 8094 不会由网关补充用户身份，APP 接口会返回“请先登录”。

## SSE 事件

正常响应仍是 `1001` 文本事件和 `1002` 结束事件。
APP 调用或参数校验失败时返回 `1004` 错误事件，随后返回 `1002`：

```text
data:{"eventType":1004,"eventData":{"code":401,"message":"请先登录"}}

data:{"eventType":1002,"eventData":null}
```

前端收到 `1004` 时显示 `eventData.message`；收到 `1002` 时结束加载状态。
新增错误事件需要前端接入，后端不会将异常伪装成模型回答。

## 会话与停止

- 本地 ID 作为历史记录的标识，百炼返回的云端 ID 单独存入 Redis，按用户和应用隔离。
- 云端映射在完成后保留 55 分钟；映射不存在时使用本地保存的问答恢复上下文。
- 正常完成保存完整回答；停止、断连和异常保存已经接收的回答，并丢弃云端映射，防止下一轮使用未展示的云端内容。
- 同一用户的同一会话只允许一个生成请求。`/ais/chat/stop?sessionId=...` 可跨实例停止，Redis 轮询间隔为 200ms；停止不依赖云端继续返回分片。
- 生成状态有一小时兜底过期时间。Redis 不可用时返回错误，不会放任并发请求同时写历史。
- 不自动重试失败的云端请求，避免重复执行购买等有副作用的插件。

## 验收

1. 不配置 `tools`，发送普通问题，应收到文本和结束事件，无空指针。
2. 连续问“我叫小明”和“我叫什么”，确认上下文保持；历史详情能读到两轮问答。
3. 配置插件后调用需登录的业务操作，确认业务接口收到当前用户的 Token。
4. 生成过程中停止或断开连接，再打开历史，确认已接收的回答存在；随后能继续提问。
5. 同一会话并发发送第二个问题，应收到错误和结束事件，不影响第一个请求。
6. 缺少应用 ID/API Key 或上游报错时，应收到错误和结束事件，不再出现 JSON/SSE 响应协商错误。

回归测试不连接百炼；真实插件地址、鉴权头、应用发布状态及地域仍需按以上步骤联调。

在 PowerShell 中运行回归测试：

```powershell
mvn -pl tj-aigc,tj-gateway -am '-Dtest=AppAgentChatServiceTest,AccountAuthFilterTest,UserInfoInterceptorTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

若本机使用 JDK 25，现有 Byte Buddy 版本需要额外传入 `'-DargLine=-Dnet.bytebuddy.experimental=true'`；
这是测试运行参数，不影响服务运行，也不需要修改生产依赖。
