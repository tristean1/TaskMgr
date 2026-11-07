import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * REQ-023（网页界面 v1）回归测试。
 *
 * <p>全部在 {@code :memory:} 库里跑，绝不碰 {@code ~/.taskmgr/tasks.db}；HTTP 服务绑
 * {@code 127.0.0.1:0}（系统分配端口），不占 8080，也不会暴露到内网。
 * 时钟固定为 2026-10-23 12:00 (+08:00)。中文结果写 UTF-8 文件，不走 stdout（铁律 3）。
 */
public class WebTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = Instant.parse("2026-10-23T04:00:00Z"); // 12:00 +08:00
    private static final Clock CLOCK = Clock.fixed(NOW, ZONE);

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : ".");
        Files.createDirectories(outDir);
        List<String> lines = new ArrayList<>();
        try (Connection conn = memory()) {
            TaskEngine engine = new TaskEngine(conn, CLOCK, ignored -> {});
            WebServer web = WebServer.start(cmd -> engine.executeFor(cmd, "web"), 0);
            int port = web.boundPort();
            try {
                serveTests(port, lines);
                jsonTests(port, lines);
                commandTests(port, conn, lines);
                absoluteTimeTests(port, conn, lines);
                routeTests(port, lines);
            } finally {
                web.stop();
            }
        } catch (Throwable t) {
            failures.add("未捕获异常：" + t);
        }

        lines.add("");
        lines.add("PASS " + passed + " checks, FAIL " + failures.size());
        for (String f : failures) {
            lines.add("FAILED: " + f);
        }
        Files.writeString(outDir.resolve("web-results.txt"), String.join("\n", lines) + "\n",
                StandardCharsets.UTF_8);
        System.out.println("PASS " + passed + ", FAIL " + failures.size());
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    // ─────────────────────────── 静态页面 ───────────────────────────

    private static void serveTests(int port, List<String> lines) throws Exception {
        lines.add("── 静态页面 ──");
        Resp page = http(port, "GET", "/", null);
        check("GET / 返回 200", page.status == 200, "status=" + page.status);
        check("GET / 是 HTML", page.body != null && page.body.contains("<html"), "");
        check("页面里有『新建任务』", page.body != null && page.body.contains("新建任务"), "");
        check("页面里有刷新按钮", page.body != null && page.body.contains("刷新"), "");
        check("页面引用 /api/tasks", page.body != null && page.body.contains("/api/tasks"), "");

        Resp index = http(port, "GET", "/index.html", null);
        check("GET /index.html 返回 200", index.status == 200, "status=" + index.status);

        Resp post = http(port, "POST", "/", "");
        check("POST / 返回 405", post.status == 405, "status=" + post.status);
    }

    // ─────────────────────────── JSON 列 / 制表 ───────────────────────────

    private static void jsonTests(int port, List<String> lines) throws Exception {
        lines.add("── JSON 输出 ──");
        Resp r = http(port, "GET", "/api/tasks?scope=all", null);
        check("GET /api/tasks 返回 200", r.status == 200, "status=" + r.status);
        check("响应是 JSON 数组", r.body != null && r.body.trim().startsWith("["), "body=" + brief(r.body));
        check("Cache-Control: no-store", "no-store".equals(r.cacheControl), "got=" + r.cacheControl);

        Resp hidden = http(port, "GET", "/api/tasks?scope=hidden", null);
        check("scope=hidden 也是数组", hidden.body != null && hidden.body.trim().startsWith("["), "");
    }

    // ─────────────────────────── 命令回环 ───────────────────────────

    private static void commandTests(int port, Connection conn, List<String> lines) throws Exception {
        lines.add("── POST /api/cmd ──");

        Resp add = post(port, "add 网页测试 -t 2026-12-01T09:00");
        check("add 回 ok=true", add.okTrue(), "out=" + brief(add.out()));
        check("add 回执带 [OK]", add.out() != null && add.out().contains("[OK]"), "out=" + brief(add.out()));

        Resp all = http(port, "GET", "/api/tasks?scope=all", null);
        check("list all 里能看到新任务", all.body != null && all.body.contains("网页测试"), "");

        Resp bad = post(port, "list 两个 参数");
        check("非法命令 ok=false", !bad.okTrue(), "out=" + brief(bad.out()));

        Resp zh = post(port, "add 中文标题带空格的任务 -t 2026-12-02T10:30 -n 备注也是中文");
        check("中文命令往返不乱码", zh.okTrue(), "out=" + brief(zh.out()));
        Resp zhList = http(port, "GET", "/api/tasks?scope=all", null);
        check("中文标题原样出现", zhList.body != null && zhList.body.contains("中文标题带空格的任务"), "");

        Resp done = post(port, "done 网页测试");
        check("done 回 ok=true", done.okTrue(), "out=" + brief(done.out()));

        Resp doneList = http(port, "GET", "/api/tasks?scope=done", null);
        check("scope=done 里能看到已完成任务", doneList.body != null && doneList.body.contains("网页测试"), "");

        Resp hiddenAdd = post(port, "add 隐藏测试 -t 2026-12-03T09:00 -hidden");
        check("加隐藏任务成功", hiddenAdd.okTrue(), "out=" + brief(hiddenAdd.out()));
        Resp normal = http(port, "GET", "/api/tasks?scope=todo", null);
        check("默认列表不含隐藏任务", normal.body != null && !normal.body.contains("隐藏测试"), "");
        Resp withHidden = http(port, "GET", "/api/tasks?scope=hidden", null);
        check("含隐藏列表能看到隐藏任务", withHidden.body != null && withHidden.body.contains("隐藏测试"), "");
    }

    // ─────────────────────────── 绝对时刻 ───────────────────────────

    private static void absoluteTimeTests(int port, Connection conn, List<String> lines) throws Exception {
        lines.add("── 绝对时刻入参（网页 datetime-local）──");

        Resp r = post(port, "add 绝对时间 -t 2026-11-09T08:00");
        check("add 接受 2026-11-09T08:00", r.okTrue(), "out=" + brief(r.out()));
        Long got = triggerAt(conn, "绝对时间");
        long want = at(2026, 11, 9, 8, 0);
        check("落库时刻正确", got != null && got == want, "got=" + got + " want=" + want);

        Resp dateOnly = post(port, "add 只有日期 -t 2026-12-25");
        check("add 接受纯日期 2026-12-25", dateOnly.okTrue(), "out=" + brief(dateOnly.out()));
        Long got2 = triggerAt(conn, "只有日期");
        check("纯日期取当天 00:00", got2 != null && got2 == at(2026, 12, 25, 0, 0), "got=" + got2);

        Resp badDate = post(port, "add 非法日期 -t 2026-13-45T08:00");
        check("非法日期被拒（不会静默落成别的时刻）", !badDate.okTrue(), "out=" + brief(badDate.out()));
    }

    // ─────────────────────────── 路由兜底 ───────────────────────────

    private static void routeTests(int port, List<String> lines) throws Exception {
        lines.add("── 路由兜底 ──");
        Resp r404 = http(port, "GET", "/nope", null);
        check("未知路径 404", r404.status == 404, "status=" + r404.status);
        Resp r404api = http(port, "GET", "/api/nope", null);
        check("未知 API 404", r404api.status == 404, "status=" + r404api.status);
    }

    // ─────────────────────────── HTTP ───────────────────────────

    private record Resp(int status, String body, String cacheControl, String raw) {
        /** /api/cmd 的响应里 ok 字段。 */
        boolean okTrue() {
            try {
                JsonObject o = JsonParser.parseString(raw).getAsJsonObject();
                return o.has("ok") && o.get("ok").getAsBoolean();
            } catch (Throwable t) {
                return false;
            }
        }

        /** /api/cmd 的响应里 out 字段。 */
        String out() {
            try {
                JsonObject o = JsonParser.parseString(raw).getAsJsonObject();
                return o.has("out") && !o.get("out").isJsonNull() ? o.get("out").getAsString() : "";
            } catch (Throwable t) {
                return "";
            }
        }
    }

    private static Resp http(int port, String method, String path, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(3000);
        c.setReadTimeout(5000);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = c.getResponseCode();
        String text;
        try (InputStream is = status >= 400 ? c.getErrorStream() : c.getInputStream()) {
            text = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new Resp(status, text, c.getHeaderField("Cache-Control"), text);
    }

    private static Resp post(int port, String cmd) throws IOException {
        return http(port, "POST", "/api/cmd", cmd);
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private static long at(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(ZONE).toInstant().toEpochMilli();
    }

    private static Long triggerAt(Connection conn, String title) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT trigger_at FROM tasks WHERE title=? ORDER BY created_at DESC LIMIT 1")) {
            ps.setString(1, title);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                long v = rs.getLong(1);
                return rs.wasNull() ? null : v;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static Connection memory() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite::memory:");
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
        } else {
            failures.add(name + (detail == null || detail.isEmpty() ? "" : "（" + detail + "）"));
        }
    }

    private static String brief(String s) {
        if (s == null) return "null";
        String one = s.replace("\n", " ⏎ ");
        return one.length() <= 120 ? one : one.substring(0, 120) + "…";
    }
}
