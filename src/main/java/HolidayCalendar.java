import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 节假日日历（REQ-021）：判断某一天是「工作日」还是「节假日」。
 *
 * <p>数据来源是公开的 {@code holiday-cn}（国务院放假安排的机器可读版，每年更新），
 * 每年一个 JSON：{@code {"year":2026,"days":[{"name":"元旦","date":"2026-01-01","isOffDay":true}, …]}}。
 * <b>它同时含「调休上班」的周末</b>（{@code isOffDay:false}），这是自己按周末算永远算不对的那几天。
 *
 * <p>三重回退，任何一层失效都不会把问候搞挂：
 * <ol>
 *   <li>本地缓存 {@code ~/.taskmgr/holidays/<year>.json}（首发联网拉一次，之后离线可用）</li>
 *   <li>缓存里没有这一天 → 用 {@code settings.properties} 的手工例外（{@code holiday.extra} / {@code work.extra}）</li>
 *   <li>还是没有 → 退回「周六周日 = 节假日」</li>
 * </ol>
 *
 * <p>JSON 解析用项目已有的 gson，不引入新依赖。
 */
public final class HolidayCalendar {

    /** 缓存目录名（相对于 {@link DaemonEndpoint#home()}）。 */
    public static final String DIR_NAME = "holidays";

    /** 手工例外的设置键：逗号分隔的 yyyy-MM-dd。extra 的假日覆盖 work 的加班（谁更需要就用专门的键）。 */
    public static final String KEY_EXTRA_OFF = "holiday.extra";
    public static final String KEY_EXTRA_WORK = "work.extra";

    private static final String URL_JSDEVIR =
            "https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/%d.json";
    private static final String URL_RAW =
            "https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/%d.json";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final Path dir;
    /** 是否允许自动联网同步（测试与离线环境可关掉）。 */
    private boolean autoSync = true;
    private final Consumer<String> log;
    /** date → 是否放假。只装已经拿到数据的年份。 */
    private final Map<LocalDate, Boolean> days = new HashMap<>();

    public HolidayCalendar(Path dir, Consumer<String> log) {
        this.dir = dir;
        this.log = (log == null) ? (s -> { }) : log;
    }

    /** 默认位置：{@code ~/.taskmgr/holidays/}。 */
    public static HolidayCalendar atHome(Consumer<String> log) {
        return new HolidayCalendar(DaemonEndpoint.home().resolve(DIR_NAME), log);
    }

    // ─────────────────────────── 判定 ───────────────────────────

    /**
     * 这一天是否「节假日」（放假，不上班/不上课）。
     * <p>优先级：手工例外 &gt; 网上数据 &gt; 周六周日。
     */
    public boolean isDayOff(LocalDate date) {
        Boolean manual = manualOverride(date);
        if (manual != null) return manual;
        Boolean known = days.get(date);
        if (known != null) return known;
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    /** 手工例外（settings.properties）。两个都写时 {@code holiday.extra} 优先。 */
    private static Boolean manualOverride(LocalDate date) {
        String iso = date.toString();
        if (containsDate(AppSettings.get(KEY_EXTRA_OFF, ""), iso)) return Boolean.TRUE;
        if (containsDate(AppSettings.get(KEY_EXTRA_WORK, ""), iso)) return Boolean.FALSE;
        return null;
    }

    private static boolean containsDate(String raw, String iso) {
        if (raw == null) return false;
        for (String part : raw.split("[,;\\s]+")) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            if (s.equals(iso)) return true;
            // 也允许只写 MM-dd（跨年时少写几个字符）
            if (s.length() == 5 && iso.endsWith(s)) return true;
        }
        return false;
    }

    /** 这一天是否有「已知」数据（区分「查过是工作日」和「没数据，按周末猜的」）。 */
    public boolean isKnown(LocalDate date) {
        return days.containsKey(date);
    }

    /** 已载入的天数（测试与状态检查用）。 */
    public int knownDays() {
        return days.size();
    }

    // ─────────────────────────── 同步 ───────────────────────────

    /**
     * 确保当年（以及 11 月起的下一年）的数据在手上。
     * <p>只在缓存缺失时联网，失败就退到周末规则；调用方可以放心每次都调。
     *
     * @return 是否有任何一个年份的数据可用
     */
    public boolean syncIfNeeded(LocalDate today) {
        if (!autoSync) return false;   // 测试/离线：不联网，直接用缓存或周末规则
        boolean ok = ensureYear(today.getYear());
        // 12 月就得知道明年元旦放哪几天，否则跨年那几天会按周末猜错
        if (today.getMonthValue() >= 11) ok = ensureYear(today.getYear() + 1) || ok;
        return ok;
    }

    /** 关掉自动联网（测试与离线环境用；关掉后 {@link #isDayOff} 只用缓存 + 周末规则）。 */
    void setAutoSync(boolean on) {
        this.autoSync = on;
    }

    /**
     * 载入某一年：有缓存就读缓存，没缓存才联网。
     *
     * @return 该年是否有可用数据
     */
    public boolean ensureYear(int year) {
        Path file = dir.resolve(year + ".json");
        if (Files.isRegularFile(file)) {
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (parse(text, year) > 0) return true;
                log.accept("[节假日] 缓存 " + file.getFileName() + " 解析为空，尝试重新下载");
            } catch (IOException e) {
                log.accept("[节假日] 读缓存失败：" + e.getMessage());
            }
        }
        return download(year);
    }

    /** 联网拉取某一年并写入缓存。全部源失败返回 false（不抛异常）。 */
    public boolean download(int year) {
        for (String pattern : new String[]{URL_JSDEVIR, URL_RAW}) {
            String url = String.format(Locale.ROOT, pattern, year);
            String body = httpGet(url);
            if (body == null) continue;
            int n = parse(body, year);
            if (n == 0) {
                log.accept("[节假日] " + url + " 返回的内容不像节假日数据，跳过");
                continue;
            }
            try {
                Files.createDirectories(dir);
                Files.writeString(dir.resolve(year + ".json"), body, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.accept("[节假日] 写入缓存失败（数据仍在内存里可用）：" + e.getMessage());
            }
            log.accept("[节假日] 已同步 " + year + " 年安排，共 " + n + " 个特殊日期");
            return true;
        }
        log.accept("[节假日] 联网失败，按周末规则判断（可在 " + AppSettings.FILE_NAME
                + " 里用 " + KEY_EXTRA_OFF + " / " + KEY_EXTRA_WORK + " 手工指定）");
        return false;
    }

    /** 解析 {@code holiday-cn} 的 JSON 进内存，返回装入的天数。 */
    int parse(String json, int year) {
        if (json == null || json.isEmpty()) return 0;
        int n = 0;
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray arr = root.has("days") ? root.getAsJsonArray("days") : null;
            if (arr == null) return 0;
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String date = asString(o, "date");
                if (date == null) continue;
                LocalDate d;
                try {
                    d = LocalDate.parse(date);
                } catch (DateTimeParseException e) {
                    continue;
                }
                if (d.getYear() != year) continue;   // 数据串年就丢弃，避免污染别的年份
                boolean off = o.has("isOffDay") && o.get("isOffDay").isJsonPrimitive()
                        && o.get("isOffDay").getAsBoolean();
                days.put(d, off);
                n++;
            }
        } catch (RuntimeException e) {
            log.accept("[节假日] 解析失败：" + e.getMessage());
            return 0;
        }
        return n;
    }

    private static String asString(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) return null;
        String s = o.get(key).getAsString();
        return (s == null || s.isEmpty()) ? null : s;
    }

    /** 简单 GET；任何失败都返回 null（网络问题不该打断守护进程）。 */
    private static String httpGet(String url) {
        HttpClient client = null;
        try {
            client = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", "TaskMgr/1.0")
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) return null;
            return resp.body();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
