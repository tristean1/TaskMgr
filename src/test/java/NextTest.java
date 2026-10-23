import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * REQ-008（add 回执只回一条）+ REQ-004（next 命令 / 无参 done 的 C 口径）回归测试。
 *
 * <p>全部在 {@code :memory:} 库里跑，绝不碰 {@code ~/.taskmgr/tasks.db}。
 * 时钟固定为 2026-10-23 12:00 (+08:00)，让「哪个 DDL 更近」是确定的。
 * 中文结果一律写 UTF-8 文件，不走 stdout（AGENTS.md 铁律 3）。
 */
public class NextTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = Instant.parse("2026-10-23T04:00:00Z"); // 12:00 +08:00
    private static final Clock CLOCK = Clock.fixed(NOW, ZONE);

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : ".");
        Files.createDirectories(outDir);
        List<String> lines = new ArrayList<>();
        try {
            selectorTests();
            req008Tests();
            req004Tests();
            fallbackTests();
            edgeTests();
        } catch (Throwable t) {
            failures.add("未捕获异常：" + t);
        }
        lines.add("PASS " + passed + " / FAIL " + failures.size());
        lines.addAll(failures);
        Files.writeString(outDir.resolve("next-results.txt"),
                String.join(System.lineSeparator(), lines) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        if (!failures.isEmpty()) System.exit(1);
    }

    // ─────────────────────────── NextSelector 纯逻辑 ───────────────────────────

    private static void selectorTests() {
        long oct25 = ms("2026-10-25T12:00:00Z"); // 20:00 +08
        long oct30 = ms("2026-10-30T12:00:00Z");

        List<NextSelector.Candidate> mixed = List.of(
                new NextSelector.Candidate("near-p1", "近的", oct25, 1, 1_000L),
                new NextSelector.Candidate("far-p3", "远但急", oct30, 3, 2_000L),
                new NextSelector.Candidate("undated", "没时间", null, 9, 500L));
        NextSelector.Candidate picked = new NextSelector().pick(mixed, NOW.toEpochMilli());
        check("默认策略：优先级 3 的远期任务插到优先级 1 前面",
                "far-p3".equals(picked.id()), "实际=" + idOf(picked));

        NextSelector.Candidate plain = new NextSelector(NextSelector.DUE_ONLY)
                .pick(mixed, NOW.toEpochMilli());
        check("DUE_ONLY 策略：只按时间，近的赢（证明策略真的可替换）",
                "near-p1".equals(plain.id()), "实际=" + idOf(plain));

        NextSelector.Candidate undatedWinsWhenNoDated = new NextSelector().pick(
                List.of(new NextSelector.Candidate("u", "没时间", null, 1, 5_000L)),
                NOW.toEpochMilli());
        check("只有无 DDL 时照样能选出来",
                undatedWinsWhenNoDated != null && "u".equals(undatedWinsWhenNoDated.id()),
                "实际=" + idOf(undatedWinsWhenNoDated));

        NextSelector.Candidate earlierCreated = new NextSelector().pick(List.of(
                        new NextSelector.Candidate("late", "后加", null, 1, 2_000L),
                        new NextSelector.Candidate("early", "先加", null, 1, 1_000L)),
                NOW.toEpochMilli());
        check("无 DDL 之间按创建时间先到先得",
                "early".equals(earlierCreated.id()), "实际=" + idOf(earlierCreated));

        check("空候选返回 null", new NextSelector().pick(List.of(), NOW.toEpochMilli()) == null, "应为 null");
        check("null 候选返回 null", new NextSelector().pick(null, NOW.toEpochMilli()) == null, "应为 null");
    }

    // ─────────────────────────── REQ-008：add 回执只回一条 ───────────────────────────

    private static void req008Tests() throws Exception {
        try (Connection conn = memory()) {
            TaskEngine e = engine(conn);
            String r = e.executeFor("add 甲 -p 1 -t year2026 month10 day25 time20:00", "cli");
            check("REQ-008：add 回执不再打整张待办表",
                    !r.contains("待办任务（TODO）"), r);
            check("REQ-008：add 回执仍确认加了哪一条",
                    r.contains("已添加") && r.contains("甲"), r);
            check("REQ-008：add 回执不含列表序号",
                    !r.contains("1. [todo]"), r);

            String timeless = e.executeFor("add 己", "cli");
            check("REQ-008：无时间任务仍保留「未识别到时间」提醒",
                    timeless.contains("未识别到时间"), timeless);
            check("REQ-008：无时间任务的回执也不打表",
                    !timeless.contains("待办任务（TODO）"), timeless);
        }
    }

    // ─────────────────────────── REQ-004：next + 无参 done ───────────────────────────

    private static void req004Tests() throws Exception {
        try (Connection conn = memory()) {
            TaskEngine e = engine(conn);
            e.executeFor("add 甲 -p 1 -t year2026 month10 day25 time20:00", "cli");
            e.executeFor("add 乙 -p 1 -t year2026 month10 day28 time20:00", "cli");

            String next = e.executeFor("next", "cli");
            check("next：选 DDL 最近的一条", next.contains("下一条：甲"), next);
            check("next：回执提示可以直接 done", next.contains("直接发 done"), next);

            String done = e.executeFor("done", "cli");
            check("next 之后无参 done 命中的就是它",
                    done.contains("任务已设为 done: \"甲\""), done);
            check("done 回执附带「下一条」", done.contains("下一条：乙"), done);

            // 指针 vs 上次提醒：谁更晚听谁的
            e.executeFor("add 丙 -p 1 -t year2026 month10 day30 time20:00", "cli");
            e.executeFor("add 丁 -p 1 -t year2026 month10 day26 time20:00", "cli");
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("UPDATE tasks SET last_notified_at=" + (NOW.toEpochMilli() + 5_000L)
                        + " WHERE title='丙'");
            }
            String laterNotified = e.executeFor("done", "cli");
            check("指针与提醒并存时取更晚的（提醒更晚 → 命中丙）",
                    laterNotified.contains("\"丙\""), laterNotified);
            check("丙 被完成后指针失效，乙 仍在待办",
                    count(conn, "SELECT COUNT(*) FROM tasks WHERE status='todo'") == 2,
                    "待办数=" + count(conn, "SELECT COUNT(*) FROM tasks WHERE status='todo'"));
        }

        // 指针指向的任务已被 done → 应退回「上次提醒的」，而不是报错
        try (Connection conn = memory()) {
            TaskEngine e = engine(conn);
            e.executeFor("add 甲 -t year2026 month10 day25 time20:00", "cli");
            e.executeFor("add 乙 -t year2026 month10 day26 time20:00", "cli");
            e.executeFor("done", "cli");                       // 指针=乙，乙 已完成
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("UPDATE tasks SET last_notified_at=" + (NOW.toEpochMilli() - 1_000L)
                        + " WHERE title='甲'");
            }
            String r = e.executeFor("done", "cli");
            check("指针失效后退回「上次提醒的」", r.contains("\"甲\""), r);
        }
    }

    // ─────────────────────────── 退路：指针丢了怎么办 ───────────────────────────

    private static void fallbackTests() throws Exception {
        try (Connection conn = memory()) {
            TaskEngine e = engine(conn);
            e.executeFor("add 戊 -t year2026 month10 day29 time20:00", "cli");
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("DELETE FROM meta WHERE key LIKE 'last_shown%'");
            }
            String r = e.executeFor("done", "cli");
            check("指针缺失时退回 next()", r.contains("\"戊\""), r);

            String auto = e.executeFor("next", "cli");
            check("没有待办时 next 给明确提示",
                    auto.contains("没有待办任务"), auto);
        }
    }

    private static void edgeTests() throws Exception {
        try (Connection conn = memory()) {
            TaskEngine e = engine(conn);
            String r = e.executeFor("done", "cli");
            check("空库无参 done 给提示而不是报错",
                    r.startsWith("[提示]") && r.contains("没有可完成"), r);

            e.executeFor("add 甲", "cli");
            String args = e.executeFor("next 甲", "cli");
            check("next 带参数时提示用法", args.contains("next 不带参数"), args);
        }
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private static Connection memory() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite::memory:");
    }

    private static TaskEngine engine(Connection conn) {
        return new TaskEngine(conn, CLOCK, ignored -> {});
    }

    private static long ms(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    private static String idOf(NextSelector.Candidate c) {
        return c == null ? "null" : c.id();
    }

    private static int count(Connection conn, String sql) {
        try (Statement st = conn.createStatement();
             var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : -1;
        } catch (Exception e) {
            return -1;
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
