# TTS 流式接入说明

`POST /ais/audio/tts` 和 `POST /ais/audio/tts-stream` 将登录用户提交的文本合成为 MP3，通过 `ResponseBodyEmitter` 逐片返回。两个路径映射到同一实现，`/tts-stream` 兼容前端语音播报按钮的调用。Service 使用 `Flux.subscribe()` 订阅 SDK 音频，并手动调用 `emitter.send()` 按顺序发送，不再聚合完整音频或阻塞等待全部生成。请求体支持原始文本或 JSON 字符串，不接受 JSON 对象或数组，不关联会话，也不保存聊天历史。STT 尚未实现。

## 配置

本地 `local` 环境已在 `application-local.yml` 中将 `tj.ai.dashscope.key` 关联到 `ALIYUN_API_KEY`。通过 IDEA 的 `AIGCApplication` 运行配置设置该环境变量后重启服务即可，无需在文件中填写密钥。环境变量缺失时默认为空，调用 TTS 时返回配置错误。

将以下内容合并到 Nacos 的 `aigc-service.yaml` 对应节点下，不要重复声明 YAML 根节点：

```yaml
tj:
  ai:
    dashscope:
      key: ${DASHSCOPE_API_KEY}
      tts:
        model: cosyvoice-v3-plus
        voice: longanyang
        base-url: wss://dashscope.aliyuncs.com/api-ws/v1/inference
        timeout-ms: 60000
        connect-timeout-ms: 10000
        max-text-length: 2000
```

API Key 与 APP 聊天模式复用，但 TTS 不需要 `app-agent.id`，在 ROUTE、ENHANCE、APP 模式下均可调用。环境变量应在 `aigc-service` 进程中设置，也可以通过现有配置管理方式注入。配置变更后重启服务。

当前实现直接调用百炼 DashScope SDK，读取 `tj.ai.dashscope`，不读取 `spring.ai.openai.audio.speech` 配置。

默认音频格式固定为 MP3、22050Hz、单声道（SDK 枚举 `MP3_22050HZ_MONO_256KBPS`），默认语速1.0、音量50。模型、音色和端点可配置；模型与音色必须匹配，并与 API Key 所属地域、工作空间一致。工作空间专属端点可填写到 `base-url`，不影响聊天客户端的全局端点。

文本长度按 Unicode 码点计算，保留提交文本的空格和换行；默认上限2000字符，可配置为1至20000。总超时与连接超时必须大于0；总超时限制整个合成与发送流程，即使持续收到分片也不会重新计时。连接超时单独限制建立连接的等待时间。

缺少 Key 或 TTS 配置无效时，仅 TTS 请求报错，不因 TTS 配置阻止服务启动。合成失败不自动重试；完成、失败、超时和客户端断连都会取消订阅并关闭本次 SDK WebSocket 连接。登录及参数校验在原请求线程完成，SDK 建连及音频发送在弹性线程池运行，按顺序发送并限制预取。

模型和音色参考：[CosyVoice 音色列表](https://help.aliyun.com/zh/model-studio/cosyvoice-voice-list)。SDK 使用方式参考：[CosyVoice Java SDK](https://help.aliyun.com/zh/model-studio/cosyvoice-java-sdk)。

## 请求与响应

通过网关10010端口调用，携带现有登录接口返回的 `Authorization` 值。网关已有 `/ais/**` 路由；服务内路径为 `/audio/tts` 或 `/audio/tts-stream`。直接访问8094不会由网关补充登录身份，不能只携带外部 Token 绕过网关调用。

```http
POST /ais/audio/tts HTTP/1.1
Authorization: <登录Token>
Content-Type: text/plain;charset=UTF-8
Accept: audio/mp3, application/json

你好，欢迎来到天机学堂。Let's learn Java!
```

也支持 `Content-Type: application/json` 配合 JSON 字符串请求体，例如 `"你好，欢迎来到天机学堂。"`。后端会先解码字符串，引号和转义不会成为播报内容。为兼容现有前端，也接受设置 JSON 请求头但实际提交原始文本的方式。`text/plain` 下的文字（含引号）始终原样提交；JSON 对象、数组和无效的 JSON 字符串返回400业务码。

音频响应为 HTTP 200、`Content-Type: audio/mp3`，响应体为连续的 MP3 二进制分片，包含 `Cache-Control: no-store`，不设置 `Content-Length`。收到有效音频帧即可发送，不等待整个合成完成；分片没有 JSON 或 SSE 包装，网络读取块的边界也不一定与 SDK 音频帧一致。

首片发送前的业务异常通过网关沿用 HTTP 200 + JSON 业务码的约定，返回 `code`、`msg`、`requestId`。业务码：400 文本参数错误；401 未登录；500 配置或上游合成失败、空音频；504 总超时。TTS 局部异常适配复用 `CommonExceptionAdvice` 的错误规则，并明确指定 JSON 类型，避免音频 `produces` 导致错误响应协商失败。直连微服务保留统一处理器的 HTTP 状态和文本错误响应。网关自身拒绝登录或网络请求失败也可能返回非200状态码。

首片发送后，HTTP 响应已经提交，无法改成 JSON 或改变状态码；发生上游异常、超时或客户端断连时结束传输并清理资源，日志保留错误信息。客户端需要处理网络读取错误和播放器错误，不能期望在 MP3 尾部读取业务码。纯 MP3 流没有业务结束事件，仅凭正常 EOF 也不能可靠判定合成是否完整。

## 前端接收后播放示例

以下示例使用 `response.blob()`，会等待响应结束后再播放；它用于简单接入，不是边接收边播放。页面准备一个带 `controls` 的 `<audio id="tts-player"></audio>`，由按钮点击等用户操作调用。`token` 使用当前登录 Token 的原始值。

边接收边播放需读取 `response.body.getReader()`，把音频块按序交给支持 MP3 的播放器，例如浏览器支持时使用 MediaSource；应先检查 `MediaSource.isTypeSupported('audio/mpeg')`。接口响应类型按约定为 `audio/mp3`，MediaSource 使用播放器支持的 MIME 类型。浏览器不支持时可回退到下方 Blob 方式。页面取消播放或卸载时，应同时用 AbortController 取消 fetch，使后端停止合成。

```javascript
const player = document.getElementById('tts-player');
let audioUrl;
let ttsRequest;

function clearTtsAudio() {
  player.pause();
  player.removeAttribute('src');
  player.load();
  if (audioUrl) URL.revokeObjectURL(audioUrl);
  audioUrl = undefined;
}

async function playTts(text, token) {
  if (ttsRequest) ttsRequest.abort();
  ttsRequest = new AbortController();
  const response = await fetch('/ais/audio/tts', {
    method: 'POST',
    signal: ttsRequest.signal,
    headers: {
      Authorization: token,
      'Content-Type': 'text/plain;charset=UTF-8',
      Accept: 'audio/mp3, application/json'
    },
    body: text
  });
  const type = response.headers.get('Content-Type') || '';
  if (type.includes('application/json')) {
    const error = await response.json();
    throw new Error(error.msg || error.message || `请求失败：${response.status}`);
  }
  if (!response.ok || !type.includes('audio/mp3')) {
    throw new Error(`语音请求失败：${response.status}`);
  }
  const blob = await response.blob();
  clearTtsAudio();
  audioUrl = URL.createObjectURL(blob);
  player.src = audioUrl;
  await player.play(); // 浏览器限制自动播放时，用户仍可点击 audio 控件播放。
}

// 用户停止播放/组件卸载时也应调用 stopTts()。
function stopTts() {
  if (ttsRequest) ttsRequest.abort();
  clearTtsAudio();
}
window.addEventListener('pagehide', stopTts);
```

## 验证

自动化测试隔离百炼，不消耗合成额度。在项目根目录的 PowerShell 执行：

```powershell
mvn -pl tj-aigc -am '-Dtest=AudioServiceTest,DashScopeTtsClientTest,AudioControllerTest,AppAgentChatServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

本机使用 JDK 25 时，增加 `'-DargLine=-Dnet.bytebuddy.experimental=true'`，与现有 APP 回归测试一致。

测试覆盖首片在合成结束前发出、帧顺序、缓冲区复制、首片前 JSON 错误、首片后不追加 JSON、发送 IOException、断连清理、总超时和并发隔离，并回归原有 APP 聊天测试。

真实账号验收：配置有效 Key、模型、音色和端点并重启服务；使用有权调用该接口的登录用户通过网关提交中文及中英混合文本，确认响应类型为 `audio/mp3`，可在请求完成前读取到音频，最终 MP3 可播放。随后提交空文本、超长文本和未登录请求，确认首片前返回 JSON 错误；生成中取消请求，确认后端关闭上游连接。将总超时临时调小，分别验证首片前 JSON 错误和首片后终止音频传输，再恢复默认配置。测试完成后检查服务日志中的上游 requestId，确认失败请求没有自动重试。
