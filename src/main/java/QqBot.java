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

    /** 控制提醒线程生命周期。 */
    private volatile boolean running = true;

    public QqBot(TaskEngine engine) {
        this.engine = engine;
    }

    // ───────────────────────────── 主入口 ─────────────────────────────
    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        String dbPath = System.getProperty("user.home") + File.separator + ".taskmgr"
                + File.separator + "tasks.db";
        File pf = new File(dbPath).getParentFile();
        if (pf != null) pf.mkdirs();
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
    private void loadConfig() throws IOException {
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
        String to = p.getProperty("remind.to", "");
        if (to != null) {
            for (String s : to.split("[,;]")) {
                String t = s.trim();
                if (!t.isEmpty()) remindTargets.add(t);
            }
        }
        System.out.println("[QQBot] appid=" + blankOr(appId) + ", 端口=" + port
                + ", 提醒目标=" + remindTargets);
        this.port = port;
    }

    private int port = 9000;

    private static String blankOr(String s) {
        return (s == null || s.isEmpty()) ? "（未配置）" : "***" + s.substring(Math.max(0, s.length() - 4));
    }
// ───────────────────────────── 启动 ─────────────────────────────
    /** 启动 Webhook 服务 + 提醒线程，并阻塞直到应用退出。 */
    public void start() throws Exception {
        startReminderLoop();
        startHttpServer();
        System.out.println("[QQBot] 已启动，Webhook 端口=" + port
                + "。请在开放平台配置回调地址指向本机的内网穿透公网地址。Ctrl+C 退出。");
        Thread.currentThread().join(); // 阻塞主线程
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
        String body = gson.toJson(Map.of(
                "appId", Long.parseLong(appId),
                "clientSecret", appSecret,
                "grant_type", "client_credentials"));
        String respText = postText("https://bots.qq.com/app/getAppAccessToken", body, "application/json", null);
        JsonObject j = JsonParser.parseString(respText).getAsJsonObject();
        String token = opt(j, "access_token");
        if (token == null || token.isEmpty()) {
            throw new IllegalStateException("获取 access_token 失败: " + respText);
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
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", this::handleCallback);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        this.server = server;
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

    /** 按事件类型分发。 */
    private void dispatchEvent(String type, JsonObject event) {
        if (type == null) { log("[Webhook] 忽略无类型事件"); return; }
        switch (type) {
            case "C2C_MESSAGE_CREATE":      handleC2c(event);      break;
            case "GROUP_AT_MESSAGE_CREATE": handleGroupAt(event);  break;
            case "GROUP_AND_C2C_EVENT":     /* 群/单聊事件，暂时忽略 */ break;
            default: log("[Webhook] 忽略事件类型: " + type);
        }
    }

    private void handleC2c(JsonObject event) {
        JsonObject d = d(event);
        String openid = opt(d, "author.user_openid");
        if (openid == null) openid = opt(d, "user_openid");
        String content = opt(d, "content");
        if (openid == null || content == null) return;
        log("[C2C] 来自 " + openid + ": " + content);
        String reply = runCommand(content.trim());
        sendC2c(openid, reply);
    }

    private void handleGroupAt(JsonObject event) {
        JsonObject d = d(event);
        String group = opt(d, "group_openid");
        if (group == null) group = opt(d, "id.group_openid");
        String content = opt(d, "content");
        if (content == null) content = opt(d, "id.content");
        if (group == null || content == null) return;
        // 去掉 @机器人的 <@!123> 标记
        String clean = content.replaceAll("<@!?[0-9]+>", "").replaceAll("<@[^>]*>", "").trim();
        log("[群@] " + group + ": " + clean);
        String reply = runCommand(clean);
        sendGroup(group, reply);
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
        Consumer<String> old = engine.getOutput();
        engine.setOutput(s -> { if (sb.length() > 0) sb.append("\n"); sb.append(s); });
        try {
            engine.execute(line);
        } finally {
            engine.setOutput(old);
        }
        return sb.length() == 0 ? "（无输出）" : sb.toString();
    }

    /** 发送单聊消息到用户 openid。 */
    private void sendC2c(String openid, String text) {
        if (openid == null || openid.isEmpty() || text == null || text.isEmpty()) return;
        try {
            String token = accessToken();
            String url = "https://api.sgroup.qq.com/v2/users/" + urlenc(openid) + "/messages";
            String body = gson.toJson(Map.of(
                    "msg_type", 0,
                    "msg_seq", (int) (System.currentTimeMillis() % 10000),
                    "content", text,
                    "openid", openid));
            String r = postText(url, body, "application/json", "QQBot " + token);
            log("[发送→私聊] " + openid + " :: " + text + (verbose ? "  resp=" + r : ""));
        } catch (Exception e) {
            log("[错误] 发送私聊失败: " + e.getMessage());
        }
    }

    /** 发送群消息到 group_openid。 */
    private void sendGroup(String group, String text) {
        if (group == null || group.isEmpty() || text == null || text.isEmpty()) return;
        try {
            String token = accessToken();
            String url = "https://api.sgroup.qq.com/v2/groups/" + urlenc(group) + "/messages";
            String body = gson.toJson(Map.of(
                    "msg_type", 0,
                    "msg_seq", System.currentTimeMillis() % 10000L,
                    "content", text,
                    "group_openid", group));
            String r = postText(url, body, "application/json", "QQBot " + token);
            log("[群消息发送] " + group + " :: " + text + (verbose ? "  resp=" + r : ""));
        } catch (Exception e) {
            log("[错误] 发送群消息失败: " + e.getMessage());
        }
    }

    /** POST 返回文本响应。 */
    private String postText(String url, String body, String contentType, String auth)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", contentType + "; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (auth != null) b.header("Authorization", auth);
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return resp.body();
    }

    private static String urlenc(String s) throws Exception {
        return URLEncoder.encode(s, StandardCharsets.UTF_8.toString());
    }

    private void log(String line) {
        System.out.println("[" + java.time.LocalTime.now().withNano(0) + "] " + line);
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
            if (server != null) server.stop(0);
        }));
    }

    /** 扫描到期任务并通知（幂等，避免重复）。 */
    private void checkReminders() {
        String sql =
            "SELECT id, title, note, trigger_type, priority, trigger_at, remind_at, last_notified_at " +
            "FROM tasks WHERE status != 'removed' AND (" +
            "  (remind_at IS NOT NULL AND remind_at <= ?) OR (trigger_at IS NOT NULL AND trigger_at <= ?)" +
            ") ORDER BY priority DESC LIMIT 20";
        long now = System.currentTimeMillis();
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    String title = rs.getString("title");
                    String note = rs.getString("note");
                    long lastNotified = rs.getLong("last_notified_at");
                    long remindAt = rs.getLong("remind_at");
                    // 判定是否已通知过：距上次通知 < 5 分钟视为已通知（重启后仍能补弹一次）
                    if (lastNotified > 0 && (now - lastNotified) < 5 * 60_000L) continue;
                    String line = buildRemindText(title, note, remindAt);
                    // 本地弹窗 + 出声
                    localAlert(line);
                    // QQ 推送
                    pushToQQ(line);
                    // 记时间戳，防止 10s 循环里反复触发
                    markNotified(c, id, now);
                }
            }
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

    /** 通过 QQ 机器人把提醒发给已登记目标。 */
    private void pushToQQ(String text) {
        if (remindTargets.isEmpty()) { log("[提醒] 未配置 remind.to，跳过 QQ 推送"); return; }
        for (String target : remindTargets) {
            if (target.startsWith("group:")) sendGroup(target.substring("group:".length()), text);
            else if (target.startsWith("user:")) sendC2c(target.substring("user:".length()), text);
            else log("[提醒] 未知目标格式(需 group: 或 user: 前缀): " + target);
        }
    }

    /** 更新 last_notified_at，防止重复提醒。 */
    private void markNotified(Connection c, String id, long now) {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE tasks SET last_notified_at=? WHERE id=?")) {
            ps.setLong(1, now);
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (Exception e) {
            log("[提醒] 记录通知失败: " + e.getMessage());
        }
    }
}