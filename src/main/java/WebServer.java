import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * REQ-023 网页界面：只绑 127.0.0.1，本机浏览器经 ssh 隧道访问。
 *
 * <p>设计口径（用户 2026-11-07 拍板）：网页是**命令拼装器**，自身不含任何业务逻辑——
 * 每个请求都被拼成 {@code add …} / {@code set …} / {@code list …} 交给 {@link TaskEngine}。
 * 业务语义只有一份，网页不可能与 CLI 漂移（本项目已因"同一份逻辑写两遍"踩过 BUG-007）。
 *
 * <p>不做实时推送：页面每次取数都重新拉一次（用户要的是「刷新按钮 + 访问即拉」）。
 *
 * <p>并发：{@link Runner} 由守护进程注入，内部对引擎串行化；本类自己不加锁。
 */
public final class WebServer {

    /** 由宿主注入的命令执行器（守护进程里会对 TaskEngine 加锁）。 */
    public interface Runner {
        String run(String cmd);
    }

    private static final int DEFAULT_PORT = 8080;

    private final HttpServer server;
    private final ExecutorService pool;
    private final Runner runner;

    private WebServer(HttpServer server, ExecutorService pool, Runner runner) {
        this.server = server;
        this.pool = pool;
        this.runner = runner;
    }

    /** 端口：settings.properties 的 web.port，默认 8080。 */
    public static int port() {
        return AppSettings.getInt("web.port", DEFAULT_PORT);
    }

    /** 开关：web.enabled，默认开（笔记本上关掉即可，免得白占一个端口）。 */
    public static boolean enabled() {
        return AppSettings.getBool("web.enabled", true);
    }

    /** 测试用：指定端口（0 = 让系统分配，再用 {@link #boundPort()} 取）。 */
    public static WebServer start(Runner runner, int port) throws IOException {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        HttpServer s = HttpServer.create(new InetSocketAddress(loopback, port), 0);
        ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "web-http");
            t.setDaemon(true);
            return t;
        });
        WebServer w = new WebServer(s, pool, runner);
        s.createContext("/", w::handle);
        s.setExecutor(pool);
        s.start();
        return w;
    }

    /** 实际绑定的端口（配置写 8080，或测试时写了 0）。 */
    public int boundPort() {
        return server.getAddress().getPort();
    }

    public static WebServer start(Runner runner) throws IOException {
        return start(runner, port());
    }

    public void stop() {
        try {
            server.stop(0);
        } catch (Throwable ignored) {
        }
        pool.shutdownNow();
    }

    // ─────────────────────────── 路由 ───────────────────────────

    private void handle(HttpExchange ex) {
        try {
            // 回环绑定不阻止恶意网页向本机发请求；拒绝跨站与 DNS 重绑定来源。
            String host = ex.getRequestHeaders().getFirst("Host");
            String origin = ex.getRequestHeaders().getFirst("Origin");
            String site = ex.getRequestHeaders().getFirst("Sec-Fetch-Site");
            boolean localHost = host != null && host.matches("(?i)(localhost|127\\.0\\.0\\.1)(:[0-9]+)?");
            if (!localHost || (origin != null && !origin.equalsIgnoreCase("http://" + host))
                    || "cross-site".equalsIgnoreCase(site)) {
                send(ex, 403, "application/json; charset=utf-8", errorJson("拒绝跨站请求").getBytes(StandardCharsets.UTF_8));
                return;
            }
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/") || path.equals("/index.html")) {
                if (!method.equals("GET")) {
                    send(ex, 405, "text/plain; charset=utf-8", "只支持 GET".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                send(ex, 200, "text/html; charset=utf-8", page().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.equals("/api/tasks") && method.equals("GET")) {
                tasks(ex);
                return;
            }
            if (path.equals("/api/cmd") && method.equals("POST")) {
                command(ex);
                return;
            }
            send(ex, 404, "text/plain; charset=utf-8", ("没有这个地址：" + path).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            try {
                send(ex, 500, "text/plain; charset=utf-8",
                        ("服务端异常：" + t).getBytes(StandardCharsets.UTF_8));
            } catch (Throwable ignored) {
            }
        } finally {
            ex.close();
        }
    }

    /** GET /api/tasks?scope=today|all|done|hidden → 引擎的 list -json 原样转发。 */
    private void tasks(HttpExchange ex) throws IOException {
        String scope = queryParam(ex.getRequestURI().getRawQuery(), "scope");
        String cmd;
        switch (scope == null ? "today" : scope) {
            case "all" -> cmd = "list all -json";
            case "done" -> cmd = "list done -json";
            case "hidden" -> cmd = "list -json -a";
            case "today" -> cmd = "list -json";
            default -> cmd = "list -json";
        }
        String out = runner(cmd);
        String trimmed = out == null ? "" : out.trim();
        boolean array = false;
        try { array = com.google.gson.JsonParser.parseString(trimmed).isJsonArray(); }
        catch (RuntimeException ignored) { }
        if (array) {
            send(ex, 200, "application/json; charset=utf-8", trimmed.getBytes(StandardCharsets.UTF_8));
        } else {
            send(ex, 200, "application/json; charset=utf-8", errorJson(out).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** POST /api/cmd，body 就是一行命令原文（UTF-8）。 */
    private void command(HttpExchange ex) throws IOException {
        String cmd;
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readNBytes(65537);
            if (bytes.length > 65536) {
                send(ex, 413, "application/json; charset=utf-8", errorJson("命令过长").getBytes(StandardCharsets.UTF_8));
                return;
            }
            cmd = new String(bytes, StandardCharsets.UTF_8).trim();
        }
        if (cmd.isEmpty()) {
            send(ex, 200, "application/json; charset=utf-8", errorJson("[提示] 空命令").getBytes(StandardCharsets.UTF_8));
            return;
        }
        String out = runner(cmd);
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        // 引擎的失败约定是两种前缀：硬错误 [错误]、软拒绝 [提示]（如「时间无法解析」「只接受一个参数」）。
        // 两者都不算成功——网页要能把「命令被拒绝」和「命令生效」分开，否则表单会静默失败。
        o.addProperty("ok", out != null && !out.startsWith("[错误]") && !out.startsWith("[提示]"));
        o.addProperty("cmd", cmd);
        o.addProperty("out", out == null ? "" : out);
        send(ex, 200, "application/json; charset=utf-8", o.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private String runner(String cmd) {
        return runner.run(cmd);
    }

    private static String errorJson(String out) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("error", out == null ? "" : out);
        return o.toString();
    }

    private static String queryParam(String rawQuery, String key) {
        if (rawQuery == null) return null;
        for (String kv : rawQuery.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(key)) return kv.substring(i + 1);
        }
        return null;
    }

    private static void send(HttpExchange ex, int code, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        // 用户要求「每次访问都从服务器拉一次」⇒ 一行缓存都不许留
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    /** 页面从 classpath 取（build 会把 src/main/resources 拷进 classes）。 */
    private static String page() throws IOException {
        try (InputStream in = WebServer.class.getResourceAsStream("/web/index.html")) {
            if (in == null) {
                // 兜底也要是合法 HTML（浏览器与测试都按 HTML 判定），并明确说是打包问题
                return "<!doctype html><html><head><meta charset=utf-8><title>页面缺失</title></head>"
                        + "<body><h1>web/index.html 没打进 classpath</h1>"
                        + "<p>检查 build.bat 的 xcopy 是否带 /S /E（resources 下有 web 子目录），"
                        + "以及 build.sh 是否 cp -r src/main/resources/. classes/。</p></body></html>";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
