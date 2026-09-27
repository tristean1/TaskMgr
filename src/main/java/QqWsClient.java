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

    /**
     * 帧处理线程：单线程串行，既避免阻塞 selector 读线程，又保证帧按序处理。
     */
    private final java.util.concurrent.ExecutorService handlerExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "qqbot-ws-handler");
                t.setDaemon(true);
                return t;
            });

    /** 重连退避，避免断线风暴。 */
    private int reconnectAttempt = 0;
    /** Resume 连续失败计数，达到阈值后退回全新 Identify。 */
    private volatile int resumeFailures = 0;

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
        // BUG-003：本次连接的"代号"。关闭信号按代号绑定，旧连接的信号不会误唤醒新连接。
        final long myGen = ++connectionGeneration;

        // BUG-003 改法 B：建新连接前先彻底清理上一条（防御性保险，杜绝两条同时存活）。
        WebSocket old = this.ws;
        if (old != null) {
            log("[WS] 建新连接前发现旧连接对象未清理，强制断开（代数 " + (myGen - 1) + "）");
            try { old.abort(); } catch (Throwable ignore) {}
            this.ws = null;
        }

        String url;
        if (resumeGatewayUrl != null) {
            url = resumeGatewayUrl;             // Resume 必须用同一网关
        } else {
            url = fetchGatewayUrl();
            resumeGatewayUrl = url;             // 记录，供后续 Resume 复用
        }
        log("[WS] 正在连接 " + url + (sessionId != null ? "（带 session 恢复）" : "（全新登录）")
                + "（代数 " + myGen + "）");

        // identify/resume 成功（READY/RESUMED）之前不重置，失败时保留退避
        WebSocket.Listener listener = new Listener(myGen);
        WebSocket socket = http.newWebSocketBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(20))
                .buildAsync(URI.create(url), listener)
                .join(); // 等待握手完成；失败抛 CompletionException

        // BUG-003 改法 C：唯一性自检——此处本应为 null；若不为 null 说明仍有并存连接。
        if (this.ws != null) {
            log("[WS] 告警：建连后仍存在旧连接对象，强制关闭（BUG-003 防御性检查）");
            try { this.ws.abort(); } catch (Throwable ignore) {}
        }
        this.ws = socket;

        // 后续动作（Identify / 心跳）由 Listener 收到 OP 10 Hello 后驱动，
        // 因为必须等 Hello 才知道 heartbeat_interval。
        awaitClose(myGen);

        // 连接结束后清空引用，避免心跳线程往已关闭的连接写数据。
        // 只有仍是本次连接时才清空，避免误清掉更新一代的连接。
        if (myGen == connectionGeneration) {
            this.ws = null;
        }
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

    /** 等待"属于本次连接代号"的关闭信号（BUG-003：信号按代绑定，旧信号不会误唤醒新连接）。 */
    private void awaitClose(long myGen) {
        synchronized (closeLock) {
            while (finishedGen < myGen) {
                try {
                    closeLock.wait(5_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (closed.get()) return;
            }
        }
    }

    private final Object closeLock = new Object();
    /** 已收到关闭信号的最大连接代号。单调递增、永不复位，避免"复位丢失"竞态。 */
    private long finishedGen = 0;

    private void signalClosed(long gen) {
        synchronized (closeLock) {
            if (gen > finishedGen) finishedGen = gen;
            closeLock.notifyAll();
        }
    }

    // ───────────────────────── 发送 ─────────────────────────

    private void send(String json) {
        WebSocket s = ws;
        if (s == null) return;
        try {
            // 必须持有返回的 future 并处理异常，否则发送静默失败（曾导致心跳发不出去、
            // 服务器 2 分钟后以 4009 Session timed out 踢连接）。
            s.sendText(json, true).whenComplete((r, ex) -> {
                if (ex != null) log("[WS] 发送失败: " + ex.getMessage());
            });
        } catch (Throwable t) {
            log("[WS] 发送异常: " + t.getMessage());
        }
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

    private synchronized void startHeartbeat(long gen) {
        if (gen != connectionGeneration) {
            log("[WS] 忽略过期连接的心跳启动（代数 " + gen + "）");
            return;
        }
        stopHeartbeat();
        heartbeatTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "qqbot-ws-heartbeat");
            t.setDaemon(true);
            return t;
        });
        // 官方建议：心跳周期取 heartbeat_interval，实际发送略提前以免边界抖动被判超时
        long period = Math.max(5_000L, (long) (heartbeatIntervalMs * 0.8));
        // BUG-004：判定"僵尸连接"的超时阈值。服务端对每个心跳都会回 op 11 ACK，
        // 因此连续 2 个多周期收不到任何入站帧，即可判定连接已死。
        final long inboundTimeout = Math.max(20_000L, (long) (heartbeatIntervalMs * 2.5));
        // 新连接的存活基准从这里开始计，避免刚建连就被误判
        lastInboundAt = System.currentTimeMillis();

        heartbeatTimer.scheduleAtFixedRate(() -> {
            // 连接换代后旧定时器不得再发（避免往已关闭连接写数据）
            if (gen != connectionGeneration || closed.get()) return;

            // ── BUG-004：僵尸连接检测（必须放在发心跳之前）──
            // 休眠/断网后 TCP 可能半开：sendText 仍"成功"，日志照打「心跳已发送」，
            // 但服务端收不到、也不再回 ACK。此时必须主动断开，QQ 才会把 bot 标记为
            // 离线并**排队**用户消息；否则 QQ 以为 bot 在线，会把消息推进死连接而丢失。
            long silent = System.currentTimeMillis() - lastInboundAt;
            if (lastInboundAt > 0 && silent > inboundTimeout) {
                log("[WS] 已 " + (silent / 1000) + "s 未收到任何服务端帧（含心跳 ACK），"
                        + "判定连接已僵死，主动断开以促使 QQ 标记离线并排队消息（代数 " + gen + "）");
                WebSocket dead = ws;
                if (dead != null) {
                    try { dead.abort(); } catch (Throwable ignore) {}
                }
                return; // abort 会触发 onClose/onError → 交回 runForever 重连
            }

            try {
                JsonObject p = new JsonObject();
                p.addProperty("op", 1);
                if (hasSeq) p.addProperty("d", lastSeq.get());
                else p.add("d", com.google.gson.JsonNull.INSTANCE); // 首次心跳 d=null
                send(p.toString());
                if (verboseHeartbeat) log("[WS] 心跳已发送 seq=" + (hasSeq ? lastSeq.get() : -1)
                        + "（距上次入站 " + (silent / 1000) + "s）");
            } catch (Throwable t) {
                log("[WS] 心跳发送失败: " + t.getMessage());
            }
        }, period, period, TimeUnit.MILLISECONDS);
        log("[WS] 心跳已启动，实际周期 " + period + "ms（服务端要求 " + heartbeatIntervalMs
                + "ms，僵死判定阈值 " + inboundTimeout + "ms）");
    }

    /** 连接代号：每次 connectOnce 真正建连前自增；用于作废旧心跳定时器、丢弃过期连接帧。 */
    private volatile long connectionGeneration = 0;
    /**
     * BUG-004：最近一次**收到任何服务端帧**的时刻（epoch 毫秒）。
     * <p>
     * 用于识别"僵尸连接"—— TCP 半开、我方 {@code sendText} 仍能写入缓冲区并打印
     * 「心跳已发送」，但服务端其实收不到、也**不再回 op 11 Heartbeat ACK**。
     * 此时连接在双方眼里都是"活"的，QQ 会把用户消息沿这条死连接推下去而丢失。
     * <p>
     * <b>不能用 {@code lastSeq} 代替</b>：它只在有事件（op 0 Dispatch）时更新，
     * 空闲连接本来就长时间不变（实测 seq=13 挂了 12 分钟仍属正常）。
     */
    private volatile long lastInboundAt = 0;
    /** 是否打印每次心跳（排查 4009 超时时打开）。 */
    private volatile boolean verboseHeartbeat = true;

    private synchronized void stopHeartbeat() {
        ScheduledExecutorService t = heartbeatTimer;
        if (t != null) {
            t.shutdownNow();
            heartbeatTimer = null;
        }
    }

    /** 仅当该连接仍是当前连接时才停心跳，避免旧连接的 onClose 误停新连接的心跳（BUG-003）。 */
    private synchronized void stopHeartbeatIfCurrent(long gen) {
        if (gen != connectionGeneration) return;
        stopHeartbeat();
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void log(String s) {
        if (logger != null) logger.accept(s);
    }

    // ───────────────────────── 帧处理 ─────────────────────────

    /**
     * 帧处理器。
     * <p>
     * 注意：{@code onText} 运行在 HttpClient 的 selector 线程上，
     * <b>绝不能在其中做阻塞操作</b>（例如获取 access_token 的同步 HTTP 请求），
     * 否则会卡住读线程，导致紧随 Hello 之后的 READY 读不出来，
     * 表现为「心跳正常但 2 分钟后 4009 Session timed out」。
     * 因此所有回包动作都丢到独立线程执行。
     */
    private class Listener implements WebSocket.Listener {
        private final long myGen;
        private final StringBuilder buf = new StringBuilder();
        /** 本连接对应的 WebSocket；op7 主动关闭时使用，避免误操作新连接。 */
        private volatile WebSocket self;

        Listener(long myGen) {
            this.myGen = myGen;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            this.self = webSocket;
            log("[WS] 连接已建立，等待 Hello…（代数 " + myGen + "）");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String text = buf.toString();
                buf.setLength(0);
                // BUG-004：任何入站帧都算"连接活着"的证据（尤其 op 11 Heartbeat ACK）。
                // 必须放在最前面，保证即使后续解析失败也能刷新存活时间。
                lastInboundAt = System.currentTimeMillis();
                // 先 request(1) 保证读线程不被后续处理阻塞，再异步处理
                webSocket.request(1);
                dispatchAsync(text);
                return null;
            }
            webSocket.request(1);
            return null;
        }

        /** 把帧处理放到独立线程，避免阻塞 selector 读线程。 */
        private void dispatchAsync(String text) {
            handlerExecutor.execute(() -> {
                try {
                    handleFrame(text);
                } catch (Throwable t) {
                    log("[WS] 处理帧失败: " + t.getMessage());
                }
            });
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log("[WS] 连接关闭 code=" + statusCode + " reason=" + reason
                    + describeCode(statusCode) + "（代数 " + myGen + "）");
            stopHeartbeatIfCurrent(myGen);

            // BUG-003：已被新连接取代的旧连接，其关闭不得影响当前会话状态
            if (myGen != connectionGeneration) {
                signalClosed(myGen);
                return null;
            }

            if (statusCode == 4914 || statusCode == 4915) {
                // 已下架/封禁，重连无意义
                log("[WS] 机器人已下架或封禁，停止重连");
                closed.set(true);
            } else if (statusCode == 4009 || statusCode == 4008 || statusCode == 4006 || statusCode == 4007) {
                // 这些错误码官方的建议是重试，但 Resume 已经反复失败时不能无限 resume，
                // 否则会陷入「Resume → 2 分钟后 4009 → 再 Resume」的死循环（实测如此）。
                resumeFailures++;
                if (resumeFailures >= 2) {
                    log("[WS] Resume 连续失败 " + resumeFailures + " 次，改为全新 Identify");
                    sessionId = null;
                    resumeGatewayUrl = null;
                    hasSeq = false;
                    resumeFailures = 0;
                }
            } else if (statusCode == 1006 || statusCode == 1005 || statusCode == 1001) {
                // P2：网络层异常断开（1006 等）不立刻清 session —— 先尝试 Resume，
                // 会话可跨较长时间补发遗漏事件（§12.12 实测可跨 3 小时）；连续多次仍未恢复再全新登录。
                resumeFailures++;
                if (resumeFailures >= 3) {
                    log("[WS] 网络异常连续 " + resumeFailures + " 次仍未恢复，改为全新 Identify");
                    sessionId = null;
                    resumeGatewayUrl = null;
                    hasSeq = false;
                    resumeFailures = 0;
                }
            } else if (statusCode != WebSocket.NORMAL_CLOSURE) {
                // 会话无效，下次全新登录
                sessionId = null;
                resumeGatewayUrl = null;
                hasSeq = false;
                resumeFailures = 0;
            }
            signalClosed(myGen);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log("[WS] 连接错误（代数 " + myGen + "）: " + error);
            stopHeartbeatIfCurrent(myGen);
            signalClosed(myGen);
        }

        /** 按 opcode 分发。 */
        private void handleFrame(String text) throws Exception {
            // BUG-003：过期连接的帧一律丢弃，避免旧连接的 Hello 在新连接上触发重复 Resume/Identify
            if (myGen != connectionGeneration) {
                return;
            }
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
                    startHeartbeat(myGen);
                    break;
                }
                case 11: // Heartbeat ACK
                    break;
                case 1: { // 服务端主动要求心跳，需立即回一个
                    JsonObject p = new JsonObject();
                    p.addProperty("op", 1);
                    if (hasSeq) p.addProperty("d", lastSeq.get());
                    else p.add("d", com.google.gson.JsonNull.INSTANCE);
                    send(p.toString());
                    break;
                }
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
                        resumeFailures = 0;   // 登录成功即视为 Resume 链路正常
                        log("[WS] 鉴权成功 READY，session_id=" + sessionId);
                        break;
                    }
                    if ("RESUMED".equals(t)) {
                        reconnectAttempt = 0;
                        resumeFailures = 0;   // Resume 成功，清零失败计数
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
                    log("[WS] 服务端要求重连（代数 " + myGen + "），主动关闭当前连接");
                    // BUG-003：不能只置标志就返回——那样旧连接还活着，会与新连接并存。
                    // 主动断开（触发 onClose/onError 回调）并补发一次本代信号，保证 awaitClose 不会永久等待。
                    WebSocket sock = self;
                    if (sock != null) {
                        try { sock.abort(); } catch (Throwable ex) { log("[WS] 主动断开失败: " + ex.getMessage()); }
                    }
                    signalClosed(myGen);
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
