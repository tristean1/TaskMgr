import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * REQ-016（push 平移任务时间）回归测试。
 *
 * <p>全部在 {@code :memory:} 库里跑，绝不碰 {@code ~/.taskmgr/tasks.db}。
 * 时钟固定为 2026-10-23 12:00 (+08:00)，让「过去/未来」的判定是确定的。
 * 中文结果一律写 UTF-8 文件，不走 stdout（AGENTS.md 铁律 3）。
 */
public class PushTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = Instant.parse("2026-10-23T04:00:00Z"); // 12:00 +08:00
    private static final Clock CLOCK = Clock.fixed(NOW, ZONE);

    private static final long HOUR = 3_600_000L;
    private static final long DAY = 86_400_000L;

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : ".");
        Files.createDirectories(outDir);
        List<String> lines = new ArrayList<>();
        try {
            basicTests();
            combineTests();
            guardTests();
            timelessTests();
            bareTargetTests();
            undoTest();
        } catch (Throwable t) {
            failures.add("未捕获异常：" + t);
        }
        lines.add("PASS " + passed + " / FAIL " + failures.size());
        lines.addAll(failures);
        Files.writeString(outDir.resolve("push-results.txt"),
                String.join(System.lineSeparator(), lines) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        if (!failures.isEmpty()) System.exit(1);
    }

    // ─────────────────────────── 基本增量 ───────────────────────────

    private static void basicTests() throws Exception {
        try (Connection conn = memory()) {
            TaskEngine e = engine(conn);
            e.executeFor("add 甲 -t year2026 month10 day25 time20:00", "cli");
            long before = triggerAt(conn, "甲");

            String r = e.executeFor("push 甲 1h", "cli");
            check("push 1h：DDL 正好推迟一小时", triggerAt(conn, "甲") - before == HOUR,
                    "delta=" + (triggerAt(conn, "甲") - before) + "｜" + r);
            check("push 回执写明推迟了哪条", r.contains("[OK]") && r.contains("推迟") && r.contains("甲"), r);
            check("push 回显前后时间", r.contains("DDL") && r.contains("→"), r);

            before = triggerAt(conn, "甲");
            e.executeFor("push 甲", "cli");
            check("省略增量 = 推迟一天", triggerAt(conn, "甲") - before == DAY,
                    "delta=" + (triggerAt(conn, "甲") - before));

            before = triggerAt(conn, "甲");
            e.executeFor("push 甲 -30m", "cli");
            check("负增量 = 提前半小时", before - triggerAt(conn, "甲") == 1_800_000L,
                    "delta=" + (before - triggerAt(conn, "甲")));

            before = triggerAt(conn, "甲");
            e.executeFor("push 甲 3天", "cli");
            check("中文单位 3天 也认", triggerAt(conn, "甲") - before == 3 * DAY,
                    "delta=" + (triggerAt(conn, "甲") - before));

            before = triggerAt(conn, "甲");
            e.executeFor("push 甲 1d12h", "cli");
            check("组合单位 1d12h", triggerAt(conn, "甲") - before == DAY + 12 * HOUR,
                    "delta=" + (triggerAt(conn, "甲") - before));
        }
    }

    // ─────────────────────────── DDL 与提醒一起平移 ───────────────────────────

    private static void combineTests() throws Exception {
        try (Connection conn = memory()) {
            TaskEngine e = engine(conn);
            e.executeFor("add 乙 -t year2026 month10 day26 time18:00 "
                    + "-a year2026 month10 day26 time17:00", "cli");
            long t0 = triggerAt(conn, "乙");
            long a0 = remindAt(conn, "乙");
            check("前提：乙 同时有 DDL 与提醒", t0 > 0 && a0 > 0 && t0 > a0,
                    "t=" + t0 + " a=" + a0);

            String r = e.executeFor("push 乙 2h", "cli");
            check("DDL 平移 2h", triggerAt(conn, "乙") - t0 == 2 * HOUR, r);
            check("提醒时间也平移 2h（间距不变）", remindAt(conn, "乙") - a0 == 2 * HOUR,
                    "delta=" + (remindAt(conn, "乙") - a0));
            check("两者间距保持不变",
                    triggerAt(conn, "乙") - remindAt(conn, "乙") == t0 - a0,
                    "间距 " + (triggerAt(conn, "乙") - remindAt(conn, "乙")) + " vs " + (t0 - a0));
        }
    }

    // ─────────────────────────── 拦截与报错 ───────────────────────────

    private static void guardTests() throws Exception {
        try (Connection conn = memory()) {
            TaskEngine e = engine(conn);
            e.executeFor("add 丙 -t year2026 month10 day24 time20:00", "cli");
            long keep = triggerAt(conn, "丙");

            String r = e.executeFor("push 丙 -100d", "cli");
            check("提前到过去一律拒绝", r.contains("已拒绝"), r);
            check("被拒绝时 DDL 一点没动", triggerAt(conn, "丙") == keep,
                    "实际=" + triggerAt(conn, "丙") + " 期望=" + keep);

            r = e.executeFor("push 丙 -x", "cli");
            check("未知开关直接报错（不并进标题）", r.contains("不认识的参数") && r.contains("-x"), r);

            r = e.executeFor("push 丙 5x", "cli");
            check("单位写错给出单位提示", r.contains("单位只能是"), r);

            r = e.executeFor("push 丙 0h", "cli");
            check("增量为 0 时不瞎改", r.contains("没有任何改动") && triggerAt(conn, "丙") == keep, r);

            r = e.executeFor("push 不存在的任务 1h", "cli");
            check("找不到任务时明确提示", r.contains("未找到未完成任务"), r);

            e.executeFor("done 丙", "cli");
            r = e.executeFor("push 丙 1h", "cli");
            check("已完成的任务不参与 push", r.contains("未找到未完成任务"), r);
        }
    }

    // ─────────────────────────── 原本没有时间的任务 ───────────────────────────

    private static void timelessTests() throws Exception {
        try (Connection conn = memory()) {
            TaskEngine e = engine(conn);
            e.executeFor("add 丁", "cli");
            check("前提：丁 没有时间", triggerAt(conn, "丁") == null && remindAt(conn, "丁") == null,
                    "t=" + triggerAt(conn, "丁"));

            String r = e.executeFor("push 丁 2h", "cli");
            check("无时间任务按「现在 + 增量」补 DDL",
                    Long.valueOf(NOW.toEpochMilli() + 2 * HOUR).equals(triggerAt(conn, "丁")),
                    "实际=" + triggerAt(conn, "丁") + "｜" + r);
            check("回执说明原本没有时间", r.contains("原本没有时间"), r);
            check("无时间任务补的时间不带提醒", remindAt(conn, "丁") == null, r);

            // 注意：丁 已经被上面的 push 补上了 DDL，负增量对它来说就是合法的「提前」了，
            // 所以要验证「无时间 + 负增量」必须换一条仍是 TIMELESS 的任务。
            e.executeFor("add 庚", "cli");
            check("前提：庚 仍没有时间", triggerAt(conn, "庚") == null, "t=" + triggerAt(conn, "庚"));
            r = e.executeFor("push 庚 -1h", "cli");
            check("无时间任务 + 负增量被拒绝", r.contains("原本没有时间") && r.contains("拒绝"), r);
            check("被拒绝时不留下半成品时间", triggerAt(conn, "庚") == null, r);
        }
    }

    // ─────────────────────────── 不写名字时的取法 ───────────────────────────

    private static void bareTargetTests() throws Exception {
        try (Connection conn = memory()) {
            TaskEngine e = engine(conn);
            e.executeFor("add 戊 -t year2026 month10 day27 time20:00", "cli");
            long before = triggerAt(conn, "戊");

            String r = e.executeFor("push 1h", "cli");
            check("无参 push 命中「最近出现的任务」", triggerAt(conn, "戊") - before == HOUR, r);
            check("无参 push 也会回显任务名", r.contains("戊"), r);
        }
    }

    // ─────────────────────────── undo 与「重新武装提醒」 ───────────────────────────

    private static void undoTest() throws Exception {
        try (Connection conn = memory()) {
            TaskEngine e = engine(conn);
            e.executeFor("add 己 -t year2026 month10 day28 time20:00", "cli");
            long t0 = triggerAt(conn, "己");
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("UPDATE tasks SET last_notified_at=12345 WHERE title='己'");
            }

            String r = e.executeFor("push 己 1h", "cli");
            check("push 回执提示提醒已重新武装", r.contains("重新武装"), r);
            check("push 清空 last_notified_at（否则新时间永不提醒）",
                    lastNotified(conn, "己") == 0, "实际=" + lastNotified(conn, "己"));
            check("op_log 记下了 push", count(conn,
                    "SELECT COUNT(*) FROM op_log WHERE op_type='push'") == 1,
                    "push 行数=" + count(conn, "SELECT COUNT(*) FROM op_log WHERE op_type='push'"));

            String u = e.executeFor("undo", "cli");
            check("undo 能撤销 push", u.contains("已恢复时间调整前的任务"), u);
            check("undo 恢复原 DDL", triggerAt(conn, "己") == t0,
                    "实际=" + triggerAt(conn, "己") + " 期望=" + t0);
            check("undo 连提醒状态一起恢复", lastNotified(conn, "己") == 12345,
                    "实际=" + lastNotified(conn, "己"));
        }
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private static Connection memory() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite::memory:");
    }

    private static TaskEngine engine(Connection conn) {
        return new TaskEngine(conn, CLOCK, ignored -> {});
    }

    private static Long triggerAt(Connection conn, String title) {
        return nullableTime(conn, title, "trigger_at");
    }

    private static Long remindAt(Connection conn, String title) {
        return nullableTime(conn, title, "remind_at");
    }

    private static Long nullableTime(Connection conn, String title, String column) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + column + " FROM tasks WHERE title=? ORDER BY created_at DESC LIMIT 1")) {
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

    private static long lastNotified(Connection conn, String title) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT last_notified_at FROM tasks WHERE title=?")) {
            ps.setString(1, title);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        } catch (Exception e) {
            return -1L;
        }
    }

    private static int count(Connection conn, String sql) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
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
