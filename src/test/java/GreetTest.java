import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * REQ-021（每日问候）+ REQ-022（隐藏任务）回归测试。
 *
 * <p>全部在 {@code :memory:} 库里跑，绝不碰 {@code ~/.taskmgr/tasks.db}；
 * {@code taskmgr.home} 指向临时目录，所以 {@code settings.properties} / {@code holidays/} 也是临时的。
 * 时钟固定为 2026-11-07（周六）10:00 (+08:00)：这天按节假日算，问候时间默认 08:30，正好在「该发」的窗口里。
 * 中文结果一律写 UTF-8 文件，不走 stdout（AGENTS.md 铁律 3）。
 */
public class GreetTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = Instant.parse("2026-11-07T02:00:00Z"); // 10:00 +08:00
    private static final Clock CLOCK = Clock.fixed(NOW, ZONE);

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : ".");
        Files.createDirectories(outDir);
        Path home = Files.createTempDirectory("tm-greet-");
        System.setProperty("taskmgr.home", home.toString());   // 隔离设置文件与节假日缓存
        AppSettings.reload();

        List<String> lines = new ArrayList<>();
        try {
            hiddenTests();
            greetCommandTests();
            tickTests();
            greetingTextTests();
        } catch (Throwable t) {
            failures.add("未捕获异常：" + t);
        }
        lines.add("PASS " + passed + " / FAIL " + failures.size());
        lines.addAll(failures);
        Files.writeString(outDir.resolve("greet-results.txt"),
                String.join(System.lineSeparator(), lines) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        if (!failures.isEmpty()) System.exit(1);
    }

    // ─────────────────────────── REQ-022 隐藏任务 ───────────────────────────

    private static void hiddenTests() throws Exception {
        Connection conn = open();
        TaskEngine e = engine(conn);

        // 课表 = 隐藏 + 三天后才触发（所以今天不该露头）
        String r = e.executeFor("add 课表 -hidden -t T+3d", "cli");
        check("add -hidden 回执说明隐藏语义", r.contains("[OK]") && r.contains("[隐藏]"), r);

        String list = e.executeFor("list", "cli");
        check("默认 list 不显示隐藏任务", !list.contains("课表"), list);
        check("默认 list 提示还有隐藏任务", list.contains("另有 1 条隐藏任务"), list);
        check("隐藏任务被挡掉时仍给出空列表提示",
                list.contains("暂无 TODO 任务"), list);

        String listA = e.executeFor("list -a", "cli");
        check("list -a 能看到隐藏任务", listA.contains("课表"), listA);
        check("list -a 行尾标记 [隐藏]", listA.contains("[隐藏]"), listA);

        String next = e.executeFor("next", "cli");
        check("next 不选隐藏任务（当天之外）", !next.contains("课表"), next);

        // 隐藏但「今天触发」→ 应当浮现
        e.executeFor("add 今天的课 -hidden -t T+2h", "cli");
        String listToday = e.executeFor("list", "cli");
        check("隐藏任务在它触发的那天出现在 list", listToday.contains("今天的课"), listToday);
        check("不触发的那条仍然藏着", !listToday.contains("课表"), listToday);
        String nextToday = e.executeFor("next", "cli");
        check("隐藏任务在它触发的那天参与 next", nextToday.contains("今天的课"), nextToday);

        // set -shown 取消隐藏
        String shown = e.executeFor("set 课表 -shown", "cli");
        check("set -shown 回显隐藏位变化", shown.contains("[OK]") && shown.contains("隐藏") && shown.contains("→"), shown);
        check("set -shown 后 hidden=0", hiddenOf(conn, "课表") == 0, "hidden=" + hiddenOf(conn, "课表"));
        String listAfterShown = e.executeFor("list", "cli");
        check("取消隐藏后默认 list 就能看到", listAfterShown.contains("课表"), listAfterShown);

        // undo 要把 hidden 也还原（RESTORABLE_COLUMNS 里有它）
        String undo = e.executeFor("undo", "cli");
        check("undo 能撤销 set -shown", undo.contains("[已撤销]"), undo);
        check("undo 后 hidden 回到 1", hiddenOf(conn, "课表") == 1, "hidden=" + hiddenOf(conn, "课表") + "｜" + undo);

        // set -hidden 把已有任务改成隐藏
        e.executeFor("add 普通任务 -t T+4d", "cli");
        String hide = e.executeFor("set 普通任务 -hidden", "cli");
        check("set -hidden 能隐藏已有任务", hide.contains("[OK]") && hiddenOf(conn, "普通任务") == 1, hide);
        String listAfterHide = e.executeFor("list", "cli");
        check("set -hidden 后默认 list 看不到", !listAfterHide.contains("普通任务"), listAfterHide);
        String noop = e.executeFor("set 普通任务 -hidden", "cli");
        check("重复 -hidden 报「没有实际改动」", noop.contains("没有实际改动"), noop);

        String bad = e.executeFor("list -x", "cli");
        check("list 未知开关报错", bad.contains("[提示] 未知的 list 开关"), bad);

        String combo = e.executeFor("list todo -a", "cli");
        check("list 状态与 -a 可组合", combo.contains("课表"), combo);

        conn.close();
    }

    // ─────────────────────────── REQ-021 greet 命令 ───────────────────────────

    private static void greetCommandTests() throws Exception {
        Connection conn = open();
        TaskEngine e = engine(conn);

        String show = e.executeFor("greet", "cli");
        check("greet 显示开关状态", show.contains("[每日问候]") && show.contains("已开启"), show);
        check("greet 显示两个时间", show.contains("工作日") && show.contains("节假日"), show);
        check("greet 显示今天按什么处理", show.contains("今天按节假日处理") || show.contains("今天按工作日处理"), show);

        String setWd = e.executeFor("greet 8:15", "cli");
        check("greet 7:30 设工作日时间", setWd.contains("[OK]") && "08:15".equals(
                AppSettings.get(GreetingService.KEY_WORKDAY, "")), setWd);

        String setHd = e.executeFor("greet holiday 9:05", "cli");
        check("greet holiday 设节假日时间", setHd.contains("[OK]") && "09:05".equals(
                AppSettings.get(GreetingService.KEY_HOLIDAY, "")), setHd);

        String off = e.executeFor("greet off", "cli");
        check("greet off 关掉问候", off.contains("[OK]")
                && !AppSettings.getBool(GreetingService.KEY_ENABLED, true), off);
        String on = e.executeFor("greet on", "cli");
        check("greet on 打开问候", on.contains("[OK]")
                && AppSettings.getBool(GreetingService.KEY_ENABLED, true), on);

        String badTime = e.executeFor("greet 25:99", "cli");
        check("greet 拒绝非法时间", badTime.contains("[错误]"), badTime);

        String noPush = e.executeFor("greet test", "cli");
        check("没有通道时 greet test 明确说发不出去", noPush.contains("[提示]"), noPush);

        conn.close();
    }

    // ─────────────────────────── REQ-021 定时发送 ───────────────────────────

    private static void tickTests() throws Exception {
        AppSettings.set(GreetingService.KEY_ENABLED, "true");
        AppSettings.set(GreetingService.KEY_HOLIDAY, "08:30");
        AppSettings.set(GreetingService.KEY_WORKDAY, "07:00");

        // ① 到点、有通道 → 发一次并记日
        {
            Connection conn = open();
            TaskEngine e = engine(conn);
            List<String> sent = new ArrayList<>();
            GreetingService g = service(e, CLOCK, sent, true);
            g.tick();
            check("到点且有通道时发送一次", sent.size() == 1, "sent=" + sent.size());
            check("发送成功后记下当天日期",
                    "2026-11-07".equals(e.metaValue(GreetingService.META_LAST_SENT)),
                    "meta=" + e.metaValue(GreetingService.META_LAST_SENT));
            g.tick();
            check("同一天不会重复发送", sent.size() == 1, "sent=" + sent.size());
            g.close();
            conn.close();
        }

        // ② 通道失败 → 不记日期，下一轮还能重试
        {
            Connection conn = open();
            TaskEngine e = engine(conn);
            List<String> sent = new ArrayList<>();
            GreetingService g = service(e, CLOCK, sent, false);
            g.tick();
            check("推送失败时不记「已发送」",
                    e.metaValue(GreetingService.META_LAST_SENT) == null,
                    "meta=" + e.metaValue(GreetingService.META_LAST_SENT));
            g.close();
            conn.close();
        }

        // ③ 没有通道 → 静默等待，不记放弃
        {
            Connection conn = open();
            TaskEngine e = engine(conn);
            GreetingService g = new GreetingService(e, holidays(), CLOCK, ignored -> { });
            g.tick();
            check("没有通道时不记发送也不记放弃",
                    e.metaValue(GreetingService.META_LAST_SENT) == null
                            && e.metaValue(GreetingService.META_GAVE_UP) == null,
                    "last=" + e.metaValue(GreetingService.META_LAST_SENT)
                            + " giveup=" + e.metaValue(GreetingService.META_GAVE_UP));
            g.close();
            conn.close();
        }

        // ④ 过了 12:00 → 记放弃，不再发
        {
            Clock late = Clock.fixed(Instant.parse("2026-11-07T04:30:00Z"), ZONE); // 12:30
            Connection conn = open();
            TaskEngine e = engine(conn);
            List<String> sent = new ArrayList<>();
            GreetingService g = service(e, late, sent, true);
            g.tick();
            check("过了 12:00 不发送", sent.isEmpty(), "sent=" + sent.size());
            check("过了 12:00 记下放弃", "2026-11-07".equals(e.metaValue(GreetingService.META_GAVE_UP)),
                    "meta=" + e.metaValue(GreetingService.META_GAVE_UP));
            g.close();
            conn.close();
        }

        // ⑤ 还没到点 → 什么都不做
        {
            Clock early = Clock.fixed(Instant.parse("2026-11-06T23:00:00Z"), ZONE); // 11-07 07:00
            Connection conn = open();
            TaskEngine e = engine(conn);
            List<String> sent = new ArrayList<>();
            GreetingService g = service(e, early, sent, true);
            g.tick();
            check("未到点不发送", sent.isEmpty() && e.metaValue(GreetingService.META_LAST_SENT) == null,
                    "sent=" + sent.size());
            g.close();
            conn.close();
        }

        // ⑥ 关掉开关 → 什么都不做
        {
            AppSettings.set(GreetingService.KEY_ENABLED, "false");
            Connection conn = open();
            TaskEngine e = engine(conn);
            List<String> sent = new ArrayList<>();
            GreetingService g = service(e, CLOCK, sent, true);
            g.tick();
            check("greet.enabled=0 时不发送", sent.isEmpty(), "sent=" + sent.size());
            g.close();
            conn.close();
            AppSettings.set(GreetingService.KEY_ENABLED, "true");
        }
    }

    // ─────────────────────────── 问候正文 ───────────────────────────

    private static void greetingTextTests() throws Exception {
        Connection conn = open();
        TaskEngine e = engine(conn);
        e.executeFor("add 体检 -t T+2h", "cli");           // 今天到点
        e.executeFor("add 远期 -t T+5d", "cli");           // 今天不该出现
        e.executeFor("add 课表 -hidden -t T+3h", "cli");   // 隐藏且今天 → 应出现
        // 逾期任务没法用 add 造（add 拒绝过去的时间），直接落库
        insertOverdue(conn, "昨天的作业", NOW.toEpochMilli() - 26L * 3600 * 1000);

        String g = e.buildGreeting();
        check("问候含当天日期", g.contains("2026年11月7日"), g);
        check("问候含星期", g.contains("星期六"), g);
        check("问候含任务小节", g.contains("今天要做的事"), g);
        check("问候含今天的任务", g.contains("体检"), g);
        check("问候含隐藏任务（当天触发）", g.contains("课表"), g);
        check("问候不含远期任务", !g.contains("远期"), g);
        conn.close();
    }

    // ─────────────────────────── 基础设施 ───────────────────────────

    private static Connection open() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite::memory:");
    }

    private static TaskEngine engine(Connection conn) {
        return new TaskEngine(conn, CLOCK, ignored -> { });
    }

    private static HolidayCalendar holidays() {
        HolidayCalendar c = HolidayCalendar.atHome(ignored -> { });
        c.setAutoSync(false);   // 测试绝不联网
        return c;
    }

    private static GreetingService service(TaskEngine e, Clock clock, List<String> sent, boolean ok) {
        GreetingService g = new GreetingService(e, holidays(), clock, ignored -> { });
        g.setPush(() -> text -> {
            sent.add(text);
            return ok;
        });
        return g;
    }

    private static int hiddenOf(Connection conn, String title) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT hidden FROM tasks WHERE title=?")) {
            ps.setString(1, title);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    /** 直接落一条已逾期任务（add 拒绝过去的时间，只能绕过引擎）。 */
    private static void insertOverdue(Connection conn, String title, long triggerAt) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tasks(id, title, status, priority, trigger_type, trigger_at, "
                        + "created_at, updated_at, hidden) VALUES(?,?,?,?,?,?,?,?,0)")) {
            ps.setString(1, java.util.UUID.randomUUID().toString());
            ps.setString(2, title);
            ps.setString(3, "todo");
            ps.setInt(4, 5);
            ps.setString(5, "once");
            ps.setLong(6, triggerAt);
            ps.setLong(7, triggerAt);
            ps.setLong(8, triggerAt);
            ps.executeUpdate();
        }
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
        } else {
            failures.add("[FAIL] " + name + " ｜ 实际输出：" + detail);
        }
    }
}
