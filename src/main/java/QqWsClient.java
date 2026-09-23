import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * QQ 开放平台机器人 —— WebSocket（Gateway）接入。
 * <p>
 * 与 Webhook 模式的区别：<b>由本机主动连到 QQ</b>，而不是等 QQ 推回调。
 * 因此<b>不需要公网 IP、不需要内网穿透、不需要域名/证书</b>，这也是本项目选择它的原因。
 * <p>
 * 握手与保活时序（依据官方文档 opcode / WebSocket 方式）：
 * <ol>
 *   <li>HTTP {@code GET /gateway} 拿到 {@code wss://} 接入点（不硬编码，沙箱环境会不同）；</li>
 *   <li>建连后服务端下发 <b>OP 10 Hello</b>，携带 {@code heartbeat_interval}；</li>
 *   <li>发送 <b>OP 2 Identify</b>（{@code token="QQBot {accessToken}"}、intents、shard），
 *       成功收到 {@code READY}（内含 {@code session_id}）；</li>
 *   <li>按周期发 <b>OP 1 Heartbeat</b>，{@code d} 为最近收到的 {@code s}（首次为 null），
 *       回包为 <b>OP 11 Heartbeat ACK</b>；</li>
 *   <li>断线重连优先发 <b>OP 6 Resume</b> 补发遗漏事件，失败再退回 Identify。</li>
 * </ol>
 * 仅使用 JDK 自带 {@link java.net.http.WebSocket}（Java 11+），不引入任何新依赖。
 */
public class QqWsClient {

    /** 接入点：GET /gateway 返回的 wss 地址。 */
    private static final String URL_GATEWAY = "https://api.sgroup.qq.com/gateway";

    /**
     * 订阅事件位：GROUP_AND_C2C_EVENT (1 << 25)。
     * 覆盖 C2C_MESSAGE_CREATE（单聊）与 GROUP_AT_MESSAGE_CREATE（群@），
     * 属于基础权限，无需额外申请；若填了无权限的 intents，网关会直接断开。
     */
    public static final int INTENTS_GROUP_AND_C2C = 1 << 25;

    /** 提供 access_token（格式 "QQBot xxx" 由本类拼接）。 */
    private final TokenSupplier tokenSupplier;
    /** 收到事件时回调：(事件类型 t, 事件体 d)。 */
    private final BiConsumer<String, JsonObject> onEvent;
    /** 日志输出。 */
    private final java.util.function.Consumer<String> logger;

    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /** 最近收到的序列号 s，心跳需回传（首次为 null）。 */
    private final AtomicLong lastSeq = new AtomicLong(0);
    private volatile boolean hasSeq = false;

    /** 恢复登录态所需（断线重连时复用）。 */
    private volatile String sessionId;
    private volatile String resumeGatewayUrl;

    private volatile WebSocket ws;
    private ScheduledExecutorService heartbeatTimer;
    private volatile long heartbeatIntervalMs = 45_000L;

    /** 重连退避，避免断线风暴。 */
    private int reconnectAttempt = 0;

    public interface TokenSupplier {
        /** 返回不带前缀的 access_token。 */
        String get() throws Exception;
    }

    public QqWsClient(TokenSupplier tokenSupplier,
                      BiConsumer<String, JsonObject> onEvent,
                      java.util.function.Consumer<String> logger) {
        this.tokenSupplier = tokenSupplier;
        this.onEvent = onEvent;
        this.logger = logger;
    }

    // ───────────────────────── 对外生命周期 ─────────────────────────

    /** 阻塞式运行：连上后由回调驱动，断线在本方法内自动重连。 */
    public void runForever() {
        while (!closed.get()) {
            try {
                connectOnce();
                // connectOnce 返回意味着连接已断开（正常 close 或异常），进入重连
            } catch (Throwable t) {
                log("[WS] 连接异常: " + t.getMessage());
            }
            if (closed.get()) break;
            long backoff = Math.min(30_000L, 1_000L * (1L << Math.min(reconnectAttempt, 5)));
            reconnectAttempt++;
            log("[WS] " + (backoff / 1000) + "s 后重连…");
            sleep(backoff);
        }
        log("[WS] 已停止");
    }

    public void close() {
        closed.set(true);
        stopHeartbeat();
        WebSocket s = ws;
        if (s != null) {
            try { s.sendClose(WebSocket.NORMAL_CLOSURE, "bye"); } catch (Throwable ignore) {}
        }
    }

    // ───────────────────────── 单次连接 ─────────────────────────

    private void connectOnce() throws Exception {
        String url = resumeGatewayUrl != null ? resumeGatewayUrl : fetchGatewayUrl();
        log("[WS] 正在连接 " + url + (sessionId != null ? "（带 session 恢复）" : "（全新登录）"));

        WebSocket.Listener listener = new Listener();
        WebSocket socket = http.newWebSocketBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(20))
                .buildAsync(URI.create(url), listener)
                .join(); // 等待握手完成；失败抛 CompletionException
        this.ws = socket;

        // 后续动作（Identify / 心跳）由 Listener 收到 OP 10 Hello 后驱动，
        // 因为必须等 Hello 才知道 heartbeat_interval。
        awaitClose();
    }

    /** 拉取通用网关地址。 */
    private String fetchGatewayUrl() throws Exception {
        String token = tokenSupplier.get();
        java.net.http.HttpRequest req = java.net.http.HttpRequest
                .newBuilder(URI.create(URL_GATEWAY))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Authorization", "QQBot " + token)
                .header("User-Agent", "TaskMgr-QqBot/1.0")
                .GET().build();
        java.net.http.HttpResponse<String> resp = http.send(req,
                java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("获取网关失败 HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonObject j = JsonParser.parseString(resp.body()).getAsJsonObject();
        String url = j.has("url") ? j.get("url").getAsString() : null;
        if (url == null || url.isEmpty()) {
            throw new IllegalStateException("网关响应缺少 url: " + resp.body());
        }
        return url;
    }

    /** 等待连接结束（Listener 内部在 onClose/onError 时释放）。 */
    private void awaitClose() {
        Object lock = closeLock;
        synchronized (lock) {
            while (!connectionFinished) {
                try {
                    lock.wait(5_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (closed.get()) return;
            }
            connectionFinished = false;
        }
    }

    private final Object closeLock = new Object();
    private boolean connectionFinished = false;

    private void signalClosed() {
        synchronized (closeLock) {
            connectionFinished = true;
            closeLock.notifyAll();
        }
    }

    // ───────────────────────── 发送 ─────────────────────────

    private void send(String json) {
        WebSocket s = ws;
        if (s == null) return;
        if (json.length() > 3800) {
            // 官方限制：发送 payload 过快/过大都可能被断，这里只做体积预警
            log("[WS] 警告：心跳/鉴权报文异常大（" + json.length() + " 字节）");
        }
        s.sendText(json, true);
    }

    /** OP 2 Identify：全新登录。 */
    private void sendIdentify() throws Exception {
        String token = tokenSupplier.get();
        JsonObject d = new JsonObject();
        d.addProperty("token", "QQBot " + token);
        d.addProperty("intents", INTENTS_GROUP_AND_C2C);
        com.google.gson.JsonArray shard = new com.google.gson.JsonArray();
        shard.add(0);
        shard.add(1); // [0,1] 表示不分片
        d.add("shard", shard);
        JsonObject props = new JsonObject();
        props.addProperty("$os", System.getProperty("os.name"));
        props.addProperty("$browser", "TaskMgr");
        props.addProperty("$device", "TaskMgr");
        d.add("properties", props);

        JsonObject p = new JsonObject();
        p.addProperty("op", 2);
        p.add("d", d);
        send(p.toString());
        log("[WS] 已发送 Identify（intents=" + INTENTS_GROUP_AND_C2C + "）");
    }

    /** OP 6 Resume：断线恢复，可补发遗漏事件。 */
    private void sendResume() throws Exception {
        String token = tokenSupplier.get();
        JsonObject d = new JsonObject();
        d.addProperty("token", "QQBot " + token);
        d.addProperty("session_id", sessionId);
        d.addProperty("seq", hasSeq ? lastSeq.get() : 0);
        JsonObject p = new JsonObject();
        p.addProperty("op", 6);
        p.add("d", d);
        send(p.toString());
        log("[WS] 已发送 Resume（seq=" + (hasSeq ? lastSeq.get() : 0) + "）");
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "qqbot-ws-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatTimer.scheduleAtFixedRate(() -> {
            try {
                JsonObject p = new JsonObject();
                p.addProperty("op", 1);
                if (hasSeq) p.addProperty("d", lastSeq.get());
                else p.add("d", com.google.gson.JsonNull.INSTANCE); // 首次心跳 d=null
                send(p.toString());
            } catch (Throwable t) {
                log("[WS] 心跳发送失败: " + t.getMessage());
            }
        }, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
        log("[WS] 心跳已启动，周期 " + heartbeatIntervalMs + "ms");
    }

    private void stopHeartbeat() {
        ScheduledExecutorService t = heartbeatTimer;
        if (t != null) {
            t.shutdownNow();
            heartbeatTimer = null;
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void log(String s) {
        if (logger != null) logger.accept(s);
    }

    // ───────────────────────── 帧处理 ─────────────────────────

    private class Listener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            log("[WS] 连接已建立，等待 Hello…");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String text = buf.toString();
                buf.setLength(0);
                try {
                    handleFrame(text);
                } catch (Throwable t) {
                    log("[WS] 处理帧失败: " + t.getMessage());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log("[WS] 连接关闭 code=" + statusCode + " reason=" + reason + describeCode(statusCode));
            stopHeartbeat();
            // 4009 可 resume；多数错误需重新 identify
            if (statusCode == 4009 || statusCode == 4008) {
                log("[WS] 该错误码可尝试 Resume");
            } else if (statusCode == 4914 || statusCode == 4915) {
                log("[WS] 机器人已下架/封禁，停止重连");
                closed.set(true);
            } else if (statusCode != WebSocket.NORMAL_CLOSURE) {
                // 会话失效，下次全新 Identify
                sessionId = null;
                resumeGatewayUrl = null;
                hasSeq = false;
            }
            signalClosed();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log("[WS] 连接错误: " + error);
            stopHeartbeat();
            signalClosed();
        }

        /** 按 opcode 分发。 */
        private void handleFrame(String text) throws Exception {
            JsonObject msg = JsonParser.parseString(text).getAsJsonObject();
            int op = msg.has("op") ? msg.get("op").getAsInt() : -1;
            switch (op) {
                case 10: { // Hello
                    JsonObject d = msg.has("d") && msg.get("d").isJsonObject()
                            ? msg.getAsJsonObject("d") : new JsonObject();
                    if (d.has("heartbeat_interval")) {
                        heartbeatIntervalMs = d.get("heartbeat_interval").getAsLong();
                    }
                    log("[WS] 收到 Hello，心跳周期=" + heartbeatIntervalMs + "ms");
                    // 有 session 优先 Resume，否则全新 Identify
                    if (sessionId != null) {
                        sendResume();
                    } else {
                        sendIdentify();
                    }
                    startHeartbeat();
                    break;
                }
                case 11: // Heartbeat ACK
                    break;
                case 0: { // Dispatch
                    if (msg.has("s") && !msg.get("s").isJsonNull()) {
                        lastSeq.set(msg.get("s").getAsLong());
                        hasSeq = true;
                    }
                    String t = msg.has("t") && !msg.get("t").isJsonNull()
                            ? msg.get("t").getAsString() : null;
                    if (t == null) break;
                    if ("READY".equals(t)) {
                        JsonObject d = msg.has("d") && msg.get("d").isJsonObject()
                                ? msg.getAsJsonObject("d") : new JsonObject();
                        if (d.has("session_id")) sessionId = d.get("session_id").getAsString();
                        reconnectAttempt = 0; // 登录成功，重置退避
                        log("[WS] 鉴权成功 READY，session_id=" + sessionId);
                        break;
                    }
                    if ("RESUMED".equals(t)) {
                        reconnectAttempt = 0;
                        log("[WS] 会话已恢复 RESUMED");
                        break;
                    }
                    JsonObject d = msg.has("d") && msg.get("d").isJsonObject()
                            ? msg.getAsJsonObject("d") : new JsonObject();
                    // 交给上层（QqBot 的 dispatchEvent 逻辑）
                    try {
                        onEvent.accept(t, d);
                    } catch (Throwable ex) {
                        log("[WS] 事件处理异常(" + t + "): " + ex.getMessage());
                    }
                    break;
                }
                case 7: // Reconnect：服务端要求重连（可 Resume）
                    log("[WS] 服务端要求重连");
                    signalClosed();
                    break;
                case 9: // Invalid Session：需重新 Identify
                    log("[WS] 会话无效，将重新登录");
                    sessionId = null;
                    hasSeq = false;
                    break;
                default:
                    log("[WS] 未处理 opcode=" + op);
            }
        }

        private String describeCode(int code) {
            switch (code) {
                case 4001: return "（无效 opcode）";
                case 4002: return "（无效 payload）";
                case 4006: return "（无效 session，需重新 identify）";
                case 4007: return "（seq 错误，需重新 identify）";
                case 4008: return "（发送过快，可 resume）";
                case 4009: return "（连接过期，可 resume）";
                case 4010: return "（无效 shard）";
                case 4011: return "（需要分片）";
                case 4012: return "（无效 version）";
                case 4013: return "（无效 intent）";
                case 4014: return "（intent 无权限）";
                case 4914: return "（机器人已下架，仅沙箱可用）";
                case 4915: return "（机器人已封禁）";
                default: return "";
            }
        }
    }
}
