import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.swing.JOptionPane;
import java.awt.Toolkit;
import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * QqBot —— 把任务管理器 M1「提醒」接入 QQ 开放平台机器人。
 * <p>
 * 功能：
 * <ol>
 *   <li><b>接收</b>：起本地 HTTP Server 承接 QQ 开放平台 Webhook 回调，解析
 *       {@code C2C_MESSAGE_CREATE}（私聊）与 {@code GROUP_AT_MESSAGE_CREATE}（群@）事件。</li>
 *   <li><b>输入命令</b>：把收到的 QQ 文本交给 {@link TaskEngine} 执行，结果回发给用户。</li>
 *   <li><b>发送</b>：调用 QQ 开放平台 C2C / 群消息接口主动下发。</li>
 *   <li><b>M1 提醒</b>：后台线程到点（提醒时刻/触发时刻）弹窗 + 出声，重启补弹且不重复
 *       （用 {@code last_notified_at} 判定），并额外通过 QQ 机器人推送给已登记的目标。</li>
 * </ol>
 * <p>配置：{@code rsc/qqbot.properties}（开发时以 classpath 上的配置覆盖，见 {@link #loadProps()}）。
 */
public class QqBot {

    private final TaskEngine engine;
    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    // ── 配置 ──
    private String appId;
    private String appSecret;
    /** 出错时是否忽略（不影响主流程）。 */
    private boolean verbose;

    // ── 提醒：QQ 推送目标列表（group:openid 或 user:openid）──
    private final List<String> remindTargets = new ArrayList<>();
    /** BUG-027：推送失败重试/弹窗去重（与 ReminderService 同一套节奏）。 */
    private final ReminderPacing pacing = new ReminderPacing();

    /** 控制提醒线程生命周期。 */
    private volatile boolean running = true;

    public QqBot(TaskEngine engine) {
        this.engine = engine;
    }

    // ───────────────────────────── 主入口 ─────────────────────────────
    public static void main(String[] args) throws Exception {
        // BUG-001：单实例保护。必须放在最前面——双实例会导致 QQ 侧互相踢连接、疯狂重连、
        // 消息被处理两次（详见 SingleInstanceLock 类注释）。
        SingleInstanceLock lock = SingleInstanceLock.acquire("qqbot", "QqBot.main");
        if (lock == null) {
            System.err.println("[QQBot] 已有另一个 QqBot 实例在运行，本次启动已取消（避免双实例互踢连接）。");
            System.err.println("        当前锁持有者：");
            System.err.println("        " + SingleInstanceLock.describeHolder("qqbot")
                    .replace(System.lineSeparator(), System.lineSeparator() + "        "));
            System.err.println("        如确认没有实例在跑，请删除锁文件后重试：");
            System.err.println("        " + new java.io.File(System.getProperty("user.home")
                    + java.io.File.separator + ".taskmgr" + java.io.File.separator + "qqbot.lock"));
            return;
        }
        try {
            runBot();
        } finally {
            lock.release();
        }
    }

    /** QqBot 的实际启动逻辑（由 {@link #main} 在取得单实例锁后调用）。 */
    private static void runBot() throws Exception {
        Class.forName("org.sqlite.JDBC");
        String dbPath = System.getProperty("user.home") + File.separator + ".taskmgr"
                + File.separator + "tasks.db";
        File pf = new File(dbPath).getParentFile();
        if (pf != null) pf.mkdirs();
        pruneOldLogs();   // BUG-004：启动时清理 7 天前的日志
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL;");
                st.execute("PRAGMA busy_timeout=3000;");
            }
            TaskEngine engine = new TaskEngine(conn);
            QqBot bot = new QqBot(engine);
            bot.loadConfig();
            bot.start();
        }
    }
// ───────────────────────────── 配置加载 ─────────────────────────────
    /** 加载 rsc/qqbot.properties（含 appid/appsecret/提醒目标/监听端口），读取失败时给出明确提示。 */
    public void loadConfig() throws IOException {
        Properties p = new Properties();
        InputStream in = QqBot.class.getClassLoader().getResourceAsStream("qqbot.properties");
        if (in == null) {
            System.err.println("[QQBot] 未找到 rsc/qqbot.properties，使用默认配置（空令牌，无法真实收发）。");
            p.setProperty("appid", "");
            p.setProperty("appsecret", "");
            p.setProperty("port", "9000");
            p.setProperty("remind.to", "");
            p.setProperty("verbose", "false");
        } else {
            p.load(in);
            in.close();
        }
        this.appId = p.getProperty("appid", "");
        this.appSecret = p.getProperty("appsecret", "");
        this.verbose = "1".equals(p.getProperty("verbose", "1"));
        int port = Integer.parseInt(p.getProperty("port", "9000"));
        // 接入模式：websocket（默认，无需公网 IP）/ webhook（需内网穿透）
        this.mode = p.getProperty("mode", "websocket").trim().toLowerCase();
        this.bindAddress = p.getProperty("bind", "127.0.0.1").trim();
        if (this.bindAddress.isEmpty()) this.bindAddress = "127.0.0.1";
        String to = p.getProperty("remind.to", "");
        if (to != null) {
            for (String s : to.split("[,;]")) {
                String t = s.trim();
                if (!t.isEmpty()) remindTargets.add(t);
            }
        }
        this.port = port;
        System.out.println("[QQBot] appid=" + blankOr(appId) + ", 模式=" + mode
                + (isWsMode() ? "" : ", 监听=" + bindAddress + ":" + port)
                + ", 提醒目标=" + remindTargets);
    }

    private int port = 9000;
    /**
     * Webhook 监听地址（BUG-017，2026-10-21 修复）。
     * <p>默认只绑回环：服务端没有入站验签，暴露到局域网/公网等于允许别人伪造事件。
     * 需要内网穿透时，穿透客户端通常在本机，回环即可；确需对外监听才在配置里改 bind。
     */
    private String bindAddress = "127.0.0.1";
    /** 接入模式：websocket | webhook。 */
    private String mode = "websocket";
    /** WebSocket 客户端（仅 websocket 模式非空）。 */
    private QqWsClient wsClient;
    /** 消息收发是否已开启（用于 Main 的 qqbot up/down 状态）。 */
    private volatile boolean messagingUp = false;

    private boolean isWsMode() {
        return !"webhook".equals(mode);
    }

    private static String blankOr(String s) {
        return (s == null || s.isEmpty()) ? "（未配置）" : "***" + s.substring(Math.max(0, s.length() - 4));
    }
// ───────────────────────────── 启动 ─────────────────────────────
    /** 启动提醒线程 + 事件接入（WebSocket 或 Webhook），并阻塞直到应用退出。 */
    public void start() throws Exception {
        startReminderLoop();
        startMessaging();
        Thread.currentThread().join(); // 阻塞主线程
    }

    /**
     * 只开启「消息收发」能力（WebSocket 或 Webhook），不启提醒扫描，也不阻塞。
     * 供主命令行 {@code qqbot up} 使用；可配合 {@link #stopMessaging()} 开关。
     */
    public synchronized void startMessaging() throws Exception {
        if (messagingUp) {
            System.out.println("[QQBot] 消息功能已在运行（请先 qqbot down）");
            return;
        }
        engine.timingTick(this::log);
        if (isWsMode()) {
            startWebSocket();
        } else {
            startHttpServer();
            System.out.println("[QQBot] 已启动，Webhook 端口=" + port
                    + "。请在开放平台配置回调地址指向本机的内网穿透公网地址。");
        }
        messagingUp = true;
        System.out.println("[QQBot] 消息收发已开启（模式=" + mode + "）");
    }

    /** 关闭消息收发（WebSocket / Webhook）。幂等。 */
    public synchronized void stopMessaging() {
        if (!messagingUp) {
            System.out.println("[QQBot] 消息收发本就处于关闭状态");
            return;
        }
        try {
            if (wsClient != null) { wsClient.close(); wsClient = null; }
        } catch (Throwable t) { log("[错误] 关闭 WebSocket 失败: " + t.getMessage()); }
        try {
            if (server != null) { server.stop(0); server = null; }
        } catch (Throwable t) { log("[错误] 关闭 Webhook 失败: " + t.getMessage()); }
        messagingUp = false;
        System.out.println("[QQBot] 消息收发已关闭");
    }

    /** 消息收发是否已开启。 */
    public boolean isUp() {
        return messagingUp;
    }

    /**
     * WebSocket 模式：本机主动连 QQ 网关，无需公网 IP。
     * 事件回调复用同一套 {@link #dispatchEvent} 逻辑，因此命令行为与 Webhook 完全一致。
     */
    private void startWebSocket() {
        wsClient = new QqWsClient(
                this::accessToken,
                (t, d) -> dispatchEvent(t, d),
                this::log);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (wsClient != null) wsClient.close();
        }));
        System.out.println("[QQBot] 已启动（WebSocket 模式，无需公网 IP）。Ctrl+C 退出。");
        new Thread(() -> wsClient.runForever(), "qqbot-ws").start();
    }

    // ───────────────────────────── access_token ─────────────────────────────
    private String cachedToken;
    private long cachedTokenExpireAt; // epoch 毫秒

    /** 获取（并缓存）应用 access_token。参考 QQ 开放平台 /app/getAppAccessToken。 */
    private synchronized String accessToken() throws Exception {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < cachedTokenExpireAt - 60_000L) return cachedToken;
        if (appId.isEmpty() || appSecret.isEmpty()) {
            throw new IllegalStateException("未配置 appid/appsecret，无法获取 access_token");
        }
        // 注意：appId 必须作为【JSON 字符串】发送。若发成数字，QQ 会返回裸 400 且无响应体，
        // 极难排查（实测 appId:"1905630086" → 200；appId:1905630086 → 400）。
        String body = gson.toJson(Map.of(
                "appId", appId.trim(),
                "clientSecret", appSecret.trim(),
                "grant_type", "client_credentials"));
        SendResult r = postJson("https://bots.qq.com/app/getAppAccessToken", body, "application/json", null);
        if (!r.ok()) {
            throw new IllegalStateException("获取 access_token 失败 HTTP " + r.statusCode() + ": " + r.body());
        }
        JsonObject j = JsonParser.parseString(r.body()).getAsJsonObject();
        String token = opt(j, "access_token");
        if (token == null || token.isEmpty()) {
            throw new IllegalStateException("获取 access_token 失败: " + r.body());
        }
        cachedToken = token;
        cachedTokenExpireAt = now + optLong(j, "expires_in", 7200L) * 1000L;
        System.out.println("[QQBot] 已获取 access_token（" + blankOr(token) + "）");
        return token;
    }

    private static long optLong(JsonObject o, String k, long def) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : def; }
        catch (Exception e) { return def; }
    }

    private static String opt(JsonObject o, String k) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null; }
        catch (Exception e) { return null; }
    }
// ───────────────────────────── Webhook 服务器 ─────────────────────────────
    private void startHttpServer() throws IOException {
        // BUG-017：默认只监回环。非回环时必须显式配置，并明确告知「无入站验签」的风险。
        HttpServer server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        server.createContext("/", this::handleCallback);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        this.server = server;
        boolean loopback = "127.0.0.1".equals(bindAddress) || "localhost".equalsIgnoreCase(bindAddress)
                || "::1".equals(bindAddress);
        if (!loopback) {
            log("[QQBot] ⚠️ Webhook 绑定在非回环地址 " + bindAddress + ":" + port
                    + "：服务端没有入站验签，任何能访问该端口的人都能伪造事件（含 add/remove）。"
                    + "请确保只在内网/本机可达。");
        } else {
            log("[QQBot] Webhook 仅监听回环 " + bindAddress + ":" + port
                    + "（内网穿透客户端在本机即可；如需对外监听请显式配置 bind）。");
        }
    }

    private HttpServer server;

    /** 处理 QQ 平台 Webhook 事件回调。 */
    private void handleEvent(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "method not allowed"); return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject event = JsonParser.parseString(body).getAsJsonObject();
            log("[QQBot] 收到事件: " + (verbose ? body : brief(body)));

            // 事件类型：优先取 t+type，兼容 id+type 两种
            String t = opt(event, "t");
            if (t == null) t = opt(event, "type");
            dispatchEvent(t, event);

            respond(exchange, 200, "{\"result\":\"ok\"}");
        } catch (Exception e) {
            log("[错误] 处理回调失败: " + e.getMessage());
            try { respond(exchange, 200, "{\"result\":\"ok\"}"); } catch (Exception ignore) {}
        }
    }

    /** 按事件类型分发（WebSocket 与 Webhook 两种模式共用）。 */
    private void dispatchEvent(String type, JsonObject event) {
        if (type == null) { log("[事件] 忽略无类型事件"); return; }
        // BUG-018（2026-10-21）：会话恢复可能重放「业务已提交、seq 尚未持久化」的事件。
        // 按「事件类型 + 消息 id」持久化去重，避免重复执行命令（尤其 undo 这种非幂等命令）。
        String dedupKey = inboundKey(type, event);
        if (dedupKey != null && engine.isInboundSeen(dedupKey)) {
            log("[事件] 重复事件已忽略：" + dedupKey);
            return;
        }
        switch (type) {
            case "C2C_MESSAGE_CREATE":      handleC2c(event);      break;
            case "GROUP_AT_MESSAGE_CREATE": handleGroupAt(event);  break;
            case "GROUP_ADD_ROBOT":         log("[事件] 机器人被加入群聊"); break;
            case "GROUP_DEL_ROBOT":         log("[事件] 机器人被移出群聊"); break;
            case "FRIEND_ADD":              log("[事件] 用户添加机器人"); break;
            case "FRIEND_DEL":              log("[事件] 用户删除机器人"); break;
            case "GROUP_AND_C2C_EVENT":     /* 群/单聊事件，暂时忽略 */ break;
            default: log("[事件] 忽略事件类型: " + type);
        }
        // 业务处理成功后才写入去重标记：崩溃窗口只可能重复一次，不会丢消息。
        if (dedupKey != null) engine.markInbound(dedupKey, System.currentTimeMillis());
    }

    /** C2C / 群@ 消息的事件幂等键（type + message id）；其它事件返回 null。 */
    private static String inboundKey(String type, JsonObject event) {
        if (!"C2C_MESSAGE_CREATE".equals(type) && !"GROUP_AT_MESSAGE_CREATE".equals(type)) return null;
        String id = opt(d(event), "id");
        return (id == null || id.isEmpty()) ? null : type + ":" + id;
    }

    private void handleC2c(JsonObject event) {
        JsonObject d = d(event);
        String openid = optPath(d, "author.user_openid");
        if (openid == null) openid = opt(d, "user_openid");
        String content = opt(d, "content");
        // BUG-006 诊断：此前这里直接 return，导致"消息没被处理"完全无迹可寻。
        // 现在把丢弃原因和原文（截断）都记下来，便于判断是字段结构不符还是真的没收到。
        if (openid == null || content == null) {
            log("[C2C] ⚠️ 丢弃一条单聊事件：openid=" + (openid == null ? "null" : openid)
                    + " content=" + (content == null ? "null" : "\"" + content + "\"")
                    + " 原文=" + brief(d.toString()));
            return;
        }
        log("[C2C] 来自 " + openid + ": " + content);
        String reply = runCommand(content.trim());
        sendC2c(openid, reply);
    }

    private void handleGroupAt(JsonObject event) {
        JsonObject d = d(event);
        String group = opt(d, "group_openid");
        if (group == null) group = optPath(d, "id.group_openid");
        String content = opt(d, "content");
        if (content == null) content = optPath(d, "id.content");
        if (group == null || content == null) return;
        // 去掉 @机器人的 <@!123> 标记
        String clean = content.replaceAll("<@!?[0-9]+>", "").replaceAll("<@[^>]*>", "").trim();
        log("[群@] " + group + ": " + clean);
        String reply = runCommand(clean);
        sendGroup(group, reply);
    }

    /**
     * 按点号路径读取嵌套字段（如 {@code author.user_openid}）。
     * 任一层缺失或非对象时返回 null，不抛异常。
     */
    private static String optPath(JsonObject o, String path) {
        if (o == null || path == null) return null;
        String[] parts = path.split("\\.");
        JsonObject cur = o;
        for (int i = 0; i < parts.length - 1; i++) {
            if (!cur.has(parts[i]) || !cur.get(parts[i]).isJsonObject()) return null;
            cur = cur.getAsJsonObject(parts[i]);
        }
        return opt(cur, parts[parts.length - 1]);
    }

    /** 从事件 JSON 中提取 d 对象（兼容 t/d 分层与扁平两种）。 */
    private static JsonObject d(JsonObject event) {
        if (event.has("d") && event.get("d").isJsonObject()) return event.getAsJsonObject("d");
        return event;
    }

    private static String brief(String body) {
        int max = 200;
        return body.length() <= max ? body : body.substring(0, max) + "…";
    }

    private void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
// ───────────────────────────── 命令执行与发送 ─────────────────────────────
    /** HttpServer 回调入口包装（调用 handleEvent）。 */
    private void handleCallback(HttpExchange exchange) {
        try { handleEvent(exchange); }
        catch (Exception e) { log("[错误] 回调处理异常: " + e); }
    }

    /** 执行一条命令，把输出聚合成单条回复文本。 */
    private String runCommand(String line) {
        StringBuilder sb = new StringBuilder();
        if (!qqCommand(line, sb)) sb.append(engine.executeFor(line, "qq"));
        return sb.length() == 0 ? "（无输出）" : sb.toString();
    }

    /**
     * QQ 侧增强命令：只处理 TaskEngine 不提供的查询/管理能力，
     * 命中返回 true，未命中返回 false（交回 TaskEngine）。
     */
    private boolean qqCommand(String line, StringBuilder out) {
        if (line == null) return false;
        String[] tokens = line.trim().split("\\s+");
        if (tokens.length == 0 || tokens[0].isEmpty()) return false;
        String cmd = tokens[0].toLowerCase();
        String[] argv = tokens.length > 1
                ? java.util.Arrays.copyOfRange(tokens, 1, tokens.length) : new String[0];
        switch (cmd) {
            case "status":  out.append(statusText());            return true;
            case "targets": out.append(targetsText(argv));       return true;
            case "ping":    out.append("pong · 提醒目标 ")
                                .append(remindTargets.size()).append(" 个"); return true;
            default:        return false;
        }
    }

    /** 组装运行状态：端口、提醒目标、未完成/已到期任务数。 */
    private String statusText() {
        StringBuilder sb = new StringBuilder();
        sb.append("【TaskMgr 状态】\n");
        sb.append("Webhook 端口：").append(port).append('\n');
        sb.append("提醒目标：").append(remindTargets.isEmpty() ? "（未配置）" : remindTargets).append('\n');
        long now = System.currentTimeMillis();
        String sql = "SELECT COUNT(*) AS total,"
                + " SUM(CASE WHEN status != 'removed' THEN 1 ELSE 0 END) AS active,"
                + " SUM(CASE WHEN status NOT IN ('done','removed') AND last_notified_at IS NULL AND ("
                + "   (remind_at IS NOT NULL AND remind_at <= ?) OR (trigger_at IS NOT NULL AND trigger_at <= ?)"
                + " ) THEN 1 ELSE 0 END) AS due"
                + " FROM tasks";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    sb.append("任务总数：").append(rs.getLong("total")).append('\n');
                    sb.append("未删除：").append(rs.getLong("active")).append('\n');
                    sb.append("待补发提醒：").append(rs.getLong("due"));
                }
            }
        } catch (Exception e) {
            sb.append("查询失败：").append(e.getMessage());
        }
        return sb.toString();
    }

    /** 查询/维护 QQ 推送目标：无参查询，set/clear 修改（仅内存，不落盘）。 */
    private String targetsText(String[] argv) {
        if (argv.length == 0 || "list".equalsIgnoreCase(argv[0])) {
            return remindTargets.isEmpty()
                    ? "提醒目标为空（请在 qqbot.properties 配置 remind.to）"
                    : "提醒目标：" + remindTargets;
        }
        switch (argv[0].toLowerCase()) {
            case "set":
                if (argv.length < 2) return "用法：targets set group:<openid>[,user:<openid>…]";
                remindTargets.clear();
                for (String s : argv[1].split("[,;]")) {
                    String t = s.trim();
                    if (!t.isEmpty()) remindTargets.add(t);
                }
                return "提醒目标已更新：" + remindTargets;
            case "clear":
                remindTargets.clear();
                return "提醒目标已清空（本次运行内生效）";
            default:
                return "未知子命令：" + argv[0] + "，可用 list|set|clear";
        }
    }

    /** HTTP 响应结果（BUG-016）：ok 同时覆盖 HTTP 状态码与 QQ 业务错误字段。 */
    record SendResult(boolean ok, int statusCode, String body, String error) {}

    /**
     * 发送单聊消息。返回是否真正发送成功。
     * <p>BUG-016（2026-10-21 修复）：原实现只返回 body、从不看 statusCode，401/429/500 全走成功路径。
     */
    private boolean sendC2c(String openid, String text) {
        if (openid == null || openid.isEmpty() || text == null || text.isEmpty()) return false;
        try {
            String token = accessToken();
            String url = "https://api.sgroup.qq.com/v2/users/" + urlenc(openid) + "/messages";
            String body = gson.toJson(Map.of(
                    "msg_type", 0,
                    "msg_seq", (int) (System.currentTimeMillis() % 10000),
                    "content", text,
                    "openid", openid));
            SendResult r = postJson(url, body, "application/json", "QQBot " + token);
            if (!r.ok()) {
                log("[错误] 发送私聊失败 " + openid + " HTTP " + r.statusCode() + ": " + r.error()
                        + (verbose ? "  body=" + r.body() : ""));
                return false;
            }
            log("[发送→私聊] " + openid + " :: " + text + (verbose ? "  resp=" + r.body() : ""));
            return true;
        } catch (Exception e) {
            log("[错误] 发送私聊失败: " + e.getMessage());
            return false;
        }
    }

    /** 发送群消息到 group_openid。返回是否真正发送成功（BUG-016）。 */
    private boolean sendGroup(String group, String text) {
        if (group == null || group.isEmpty() || text == null || text.isEmpty()) return false;
        try {
            String token = accessToken();
            String url = "https://api.sgroup.qq.com/v2/groups/" + urlenc(group) + "/messages";
            String body = gson.toJson(Map.of(
                    "msg_type", 0,
                    "msg_seq", System.currentTimeMillis() % 10000L,
                    "content", text,
                    "group_openid", group));
            SendResult r = postJson(url, body, "application/json", "QQBot " + token);
            if (!r.ok()) {
                log("[错误] 发送群消息失败 " + group + " HTTP " + r.statusCode() + ": " + r.error()
                        + (verbose ? "  body=" + r.body() : ""));
                return false;
            }
            log("[群消息发送] " + group + " :: " + text + (verbose ? "  resp=" + r.body() : ""));
            return true;
        } catch (Exception e) {
            log("[错误] 发送群消息失败: " + e.getMessage());
            return false;
        }
    }

    /** POST JSON 并判定成功（HTTP 2xx 且无业务错误字段）。包级可见：供离线回归测试直接验证。 */
    SendResult postJson(String url, String body, String contentType, String auth)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", contentType + "; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (auth != null) b.header("Authorization", auth);
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int code = resp.statusCode();
        String respBody = resp.body();
        if (code / 100 != 2) return new SendResult(false, code, respBody, "HTTP " + code);
        String businessError = businessError(respBody);
        if (businessError != null) return new SendResult(false, code, respBody, businessError);
        return new SendResult(true, code, respBody, "");
    }

    /** QQ 业务错误：HTTP 200 也可能携带 code/err_code 非 0。 */
    private static String businessError(String body) {
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            for (String k : new String[]{"code", "err_code"}) {
                if (o.has(k) && !o.get(k).isJsonNull()) {
                    long v = o.get(k).getAsLong();
                    if (v != 0) return "code=" + v + " " + opt(o, "message");
                }
            }
        } catch (Exception ignore) {
            // 非 JSON（例如网关错误页）→ 不判业务错误，交给 HTTP 状态码判定
        }
        return null;
    }

    private static String urlenc(String s) throws Exception {
        return URLEncoder.encode(s, StandardCharsets.UTF_8.toString());
    }

    private void log(String line) {
        String stamped = "[" + java.time.LocalTime.now().withNano(0) + "] " + line;
        System.out.println(stamped);
        // BUG-004 诊断：控制台缓冲区一满就覆盖，已两次导致故障现场丢失（09-24 / 09-25）。
        // 这里同时追加写入文件，保留可回查的日志。写失败绝不影响主流程。
        appendLogFile(stamped);
    }

    // ───────────────────── 文件日志（BUG-004 诊断用）─────────────────────
    /** 日志目录：~/.taskmgr/logs/ ；按日一个文件，超过 7 天自动清理。 */
    private static final java.io.File LOG_DIR =
            new java.io.File(System.getProperty("user.home") + java.io.File.separator
                    + ".taskmgr" + java.io.File.separator + "logs");
    /** 每天最多写多少字节后停止追加，避免异常刷屏把磁盘写满（约 5MB）。 */
    private static final long LOG_MAX_BYTES = 5L * 1024 * 1024;
    private static volatile boolean logFileDisabled = false;

    private static synchronized void appendLogFile(String line) {
        if (logFileDisabled) return;
        try {
            if (!LOG_DIR.exists() && !LOG_DIR.mkdirs()) {
                logFileDisabled = true;
                return;
            }
            java.io.File f = new java.io.File(LOG_DIR,
                    "qqbot-" + java.time.LocalDate.now() + ".log");
            if (f.exists() && f.length() > LOG_MAX_BYTES) return; // 超限则本轮不再写
            try (java.io.FileWriter w = new java.io.FileWriter(f, java.nio.charset.StandardCharsets.UTF_8, true)) {
                w.write(line);
                w.write(System.lineSeparator());
            }
        } catch (Throwable t) {
            // 日志写失败不影响任何功能：静默关闭文件日志，避免反复抛异常刷屏
            logFileDisabled = true;
        }
    }

    /** 清理 7 天前的日志文件（启动时调用一次即可）。 */
    static void pruneOldLogs() {
        try {
            if (!LOG_DIR.isDirectory()) return;
            long cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
            java.io.File[] files = LOG_DIR.listFiles();
            if (files == null) return;
            for (java.io.File f : files) {
                if (f.isFile() && f.getName().startsWith("qqbot-") && f.lastModified() < cutoff) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        } catch (Throwable ignore) {
        }
    }
// ───────────────────────────── M1 提醒 ─────────────────────────────
    /**
     * 启动提醒扫描线程（M1）：
     * <ul>
     *   <li>每 10s 扫描一次，把「已到提醒/触发时刻且尚未通知」的任务揪出来；</li>
     *   <li>本地弹窗（JOptionPane）+ 出声（Toolkit.beep）；</li>
     *   <li>通过 QQ 机器人推送给 remindTargets；</li>
     *   <li>用 {@code last_notified_at} 保证「重启补弹且不重复」。</li>
     * </ul>
     */
    private void startReminderLoop() {
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "qqbot-reminder");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(this::checkReminders, 5, 10, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            timer.shutdown();
            engine.shutdownTiming(this::log);
            if (server != null) server.stop(0);
        }));
    }

    /** 扫描到期任务并通知（幂等，避免重复）。 */
    private void checkReminders() {
        engine.timingTick(message -> {
            log(message);
            if (messagingUp) pushToQQ(message);
        });
        // 使用 ReminderService 相同的SQL逻辑
        // BUG-007（2026-10-19 修复）：必须排除 done，否则已完成的任务到 DDL 仍会被推送。
        // 注意：本处与 ReminderService 是两个独立扫描器，必须同时改，否则两套运行方式行为不一致。
        String sql = "SELECT id,title,note,remind_at,last_notified_at FROM tasks WHERE status NOT IN ('done','removed') AND ( (remind_at IS NOT NULL AND remind_at<=?) OR (trigger_at IS NOT NULL AND trigger_at<=?) )";
        long now = System.currentTimeMillis();
        Set<String> seen = new HashSet<>();
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    seen.add(id);
                    long lastNotified = rs.getLong("last_notified_at");

                    // 不重复判定：last_notified_at IS NULL = 该提醒尚未通知 → 补发
                    // 已通知则跳过。这比"5分钟窗口"更贴合"重启补发且不重复"
                    if (lastNotified != 0) {
                        continue;
                    }
                    // BUG-027：推送失败的提醒按退避重试，不每 10 秒刷屏
                    if (!pacing.shouldAttempt(id, now)) {
                        continue;
                    }

                    String title = rs.getString("title");
                    String note = rs.getString("note");
                    long remindAt = rs.getLong("remind_at");
                    String line = buildRemindText(title, note, remindAt);
                    // 本地弹窗：每个任务只弹一次（重试推送时不再重复打扰）
                    if (pacing.markAlerted(id)) localAlert(line);

                    // BUG-027（附六 §6.5）：推送成功才写 last_notified_at；
                    // 失败则保留未提醒状态，下一轮重试（而不是把提醒永久烧掉）。
                    boolean delivered = !messagingUp || pushToQQ(line);
                    if (delivered && markNotified(c, id, now)) {
                        pacing.onSuccess(id);
                        log("[提醒] 已触发: " + title);
                    } else {
                        pacing.onFailure(id, now);
                        log("[提醒] 推送/标记失败，稍后重试: " + title);
                    }
                }
            }
            pacing.retain(seen);
        } catch (Exception e) {
            log("[提醒] 扫描失败: " + e.getMessage());
        }
    }

    private Connection conn() throws Exception {
        return java.sql.DriverManager.getConnection("jdbc:sqlite:"
                + System.getProperty("user.home") + File.separator + ".taskmgr" + File.separator + "tasks.db");
    }

    /** 拼装提醒正文。 */
    private String buildRemindText(String title, String note, long remindAt) {
        String when = remindAt > 0
                ? java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                        .withZone(java.time.ZoneId.systemDefault())
                        .format(java.time.Instant.ofEpochMilli(remindAt)) : "到点";
        String line = "【任务提醒 · " + when + "】\n" + title;
        if (note != null && !note.isEmpty()) line += "\n备注：" + note;
        return line;
    }

    /** 本地弹窗（EDT）+ 出声（beep）。 */
    private void localAlert(String text) {
        try {
            javax.swing.SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                    null, text, "任务提醒", JOptionPane.INFORMATION_MESSAGE));
        } catch (Throwable t) {
            log("[提醒] 弹窗失败: " + t.getMessage());
        }
        try { Toolkit.getDefaultToolkit().beep(); } catch (Throwable ignore) {}
    }

    /**
     * 通过 QQ 机器人把提醒发给已登记目标。
     * @return 是否全部成功；未配置任何目标时返回 true（没有可投递的通道）
     */
    private boolean pushToQQ(String text) {
        if (remindTargets.isEmpty()) { log("[提醒] 未配置 remind.to，跳过 QQ 推送"); return true; }
        boolean allOk = true;
        for (String target : remindTargets) {
            // 前缀分隔符容忍 : 与 . 两种写法（user:xxx / user.xxx），避免配置手误导致静默不推送
            String kind, id;
            int c = target.indexOf(':');
            int dot = target.indexOf('.');
            int sep = (c >= 0 && (dot < 0 || c < dot)) ? c : dot;
            if (sep < 0) {
                log("[提醒] 未知目标格式(需 user: 或 group: 前缀): " + target);
                allOk = false;
                continue;
            }
            kind = target.substring(0, sep).trim().toLowerCase();
            id = target.substring(sep + 1).trim();
            if (id.isEmpty()) {
                log("[提醒] 目标缺少 openid: " + target);
                allOk = false;
                continue;
            }
            if ("group".equals(kind)) {
                if (!sendGroup(id, text)) allOk = false;
            } else if ("user".equals(kind)) {
                if (!sendC2c(id, text)) allOk = false;
            } else {
                log("[提醒] 未知目标类型(需 user/group): " + target);
                allOk = false;
            }
        }
        return allOk;
    }

    /**
     * 对外暴露的提醒推送入口。
     * @return 是否推送成功；供 ReminderService 决定要不要写 last_notified_at（BUG-027）
     */
    public boolean pushRemind(String text) {
        return pushToQQ(text);
    }

    /** 更新 last_notified_at，防止重复提醒。@return 是否真的写入（行数=1） */
    private boolean markNotified(Connection c, String id, long now) {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE tasks SET last_notified_at=? WHERE id=? AND last_notified_at IS NULL")) {
            ps.setLong(1, now);
            ps.setString(2, id);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            log("[提醒] 记录通知失败: " + e.getMessage());
            return false;
        }
    }
}
