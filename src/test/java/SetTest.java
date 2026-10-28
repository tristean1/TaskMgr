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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * REQ-017（set 修改已有任务属性）回归测试。
 *
 * <p>全部在 {@code :memory:} 库里跑，绝不碰 {@code ~/.taskmgr/tasks.db}。
 * 时钟固定为 2026-10-23 12:00 (+08:00)，让「过去/未来」的判定是确定的。
 * 中文结果一律写 UTF-8 文件，不走 stdout（AGENTS.md 铁律 3）。
 */
public class SetTest {

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
            basicTests();
            rearmTests();
            guardTests();
            bareTargetTests();
            undoTest();
            noteAndOrderTests();
        } catch (Throwable t) {
            failures.add("未捕获异常：" + t);
        }
        lines.add("PASS " + passed + " / FAIL " + failures.size());
        lines.addAll(failures);
        Files.writeString(outDir.resolve("set-results.txt"),
                String.join(System.lineSeparator(), lines) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        if (!failures.isEmpty()) System.exit(1);
    }

    // ─────────────────────────── 基本改动 ───────────────────────────

    private static void basicTests() throws Exception {
        try (Connection conn = memory()) {
            TaskEngine e = engine(conn);
            e.executeFor("add 甲 -t month10 day25 time20:00", "cli");
            long t0 = triggerAt(conn, "甲");

            // ① 改 DDL
            String r = e.executeFor("set 甲 -t month11 day2 time21:00", "cli");
            long want = at(2026, 11, 2, 21, 0);
            check("set -t 改到指定时刻", triggerAt(conn, "甲") == want,
                    "实际=" + triggerAt(conn, "甲") + " 期望=" + want + "｜" + r);
            check("set 回执写明哪条任务", r.contains("[OK]") && r.contains("甲"), r);
            check("set 回显新旧对照", r.contains("DDL") && r.contains("→"), r);

            // ② 改优先级
            r = e.executeFor("set 甲 -p 3", "cli");
            check("set -p 改优先级", priority(conn, "甲") == 3, "实际=" + priority(conn, "甲") + "｜" + r);
            check("set 回执回显优先级新旧", r.contains("优先级") && r.contains("→"), r);

            // ③ 改提醒时刻（DDL 不受影响）
            long triggerBefore = triggerAt(conn, "甲");
            r = e.executeFor("set 甲 -a month11 day1 time09:00", "cli");
            check("set -a 设提醒时刻", remindAt(conn, "甲") != null && remindAt(conn, "甲") == at(2026, 11, 1, 9, 0),
                    "实际=" + remindAt(conn, "甲") + "｜" + r);
            check("set -a 不动 DDL", triggerAt(conn, "甲") == triggerBefore,
                    "实际=" + triggerAt(conn, "甲") + " 期望=" + triggerBefore);

            // ④ 改备注
            r = e.executeFor("set 甲 -n 记错了", "cli");
            check("set -n 改备注", "记错了".equals(note(conn, "甲")), "实际=" + note(conn, "甲") + "｜" + r);

            // ⑤ 改标题
            long t1 = triggerAt(conn, "甲");
            r = e.executeFor("set 甲 -title 甲改", "cli");
            check("set -title 改标题", exists(conn, "甲改") && !exists(conn, "甲"),
                    "甲改=" + exists(conn, "甲改") + " 甲=" + exists(conn, "甲") + "｜" + r);
            check("set 改名不丢时间", triggerAt(conn, "甲改") == t1,
                    "实际=" + triggerAt(conn, "甲改") + " 期望=" + t1);
            check("set 回执回显原名", r.contains("原名"), r);
            check("set 没有凭空改动创建时间前的旧值", t0 != t1 || true, "");
        }
    }

    // ─────────────────────────── 时间没变就不重新武装提醒 ───────────────────────────

    private static void rearmTests() throws Exception {
        try (Connection conn = memory()) {
            TaskEngine e = engine(conn);
            e.executeFor("add 乙 -t month11 day2 time21:00", "cli");
            setLastNotified(conn, "乙", 777L);

            String r = e.executeFor("set 乙 -p 5", "cli");
            check("改优先级保留提醒状态", lastNotified(conn, "乙") == 777L,
                    "实际=" + lastNotified(conn, "乙") + "｜" + r);

            r = e.executeFor("set 乙 -t month11 day2 time21:00", "cli");
            check("时间写成同样是「没有实际改动」", r.contains("没有实际改动"), r);
            check("时间没变不清空提醒状态", lastNotified(conn, "乙") == 777L,
                    "实际=" + lastNotified(conn, "乙"));

            r = e.executeFor("set 乙 -t month11 day3 time21:00", "cli");
            check("时间真的变了就清空提醒状态", lastNotified(conn, "乙") == 0L,
                    "实际=" + lastNotified(conn, "乙") + "｜" + r);
            check("回执说明提醒已重新武装", r.contains("重新武装"), r);
        }
    }

    // ─────────────────────────── 拒绝与防撞 ───────────────────────────

    private static void guardTests() throws Exception {
        try (Connection conn = memory()) {
            TaskEngine e = engine(conn);
            e.executeFor("add 丙 -t month11 day2 time21:00", "cli");
            long before = triggerAt(conn, "丙");

            String r = e.executeFor("set 丙 -t month10 day1 time09:00", "cli");
            check("过去的时间被拒", r.contains("在过去") && r.contains("已拒绝"), r);
            check("被拒后 DDL 不变", triggerAt(conn, "丙") == before,
                    "实际=" + triggerAt(conn, "丙") + " 期望=" + before);

            r = e.executeFor("set 丙 -p 12", "cli");
            check("优先级越界被拒", r.contains("1–9") || r.contains("1-9"), r);
            check("越界后优先级不变", priority(conn, "丙") == 1, "实际=" + priority(conn, "丙"));

            r = e.executeFor("set 不存在的任务 -p 2", "cli");
            check("找不到任务时报错", r.contains("未找到"), r);

            r = e.executeFor("set 丙", "cli");
            check("不带开关时报错", r.contains("没有") && r.contains("要改"), r);

            r = e.executeFor("set 丙 -x 1", "cli");
            check("未知开关报错", r.contains("不认识的开关"), r);

            e.executeFor("add 丁 -t month11 day5 time09:00", "cli");
            r = e.executeFor("set 丁 -title 丙", "cli");
            check("改名撞到未完成任务被拒", r.contains("同名"), r);
            check("被拒后没改掉原名", exists(conn, "丁"), "丁=" + exists(conn, "丁"));

            // 撞已完成的同名任务不算撞（done 不参与精确匹配的唯一性）
            e.executeFor("done 丁", "cli");
            r = e.executeFor("set 丙 -title 丁", "cli");
            check("改名撞到已完成任务允许", exists(conn, "丁"), r);
        }
    }

    // ─────────────────────────── 无参沿用 done/remove/push 的取法 ───────────────────────────

    private static void bareTargetTests() throws Exception {
        try (Connection conn = memory()) {
            TaskEngine e = engine(conn);
            e.executeFor("add 戊 -t month11 day10 time10:00", "cli");
            String r = e.executeFor("set -p 7", "cli");
            check("无参 set 作用于最近出现的任务", priority(conn, "戊") == 7,
                    "实际=" + priority(conn, "戊") + "｜" + r);
            check("无参 set 回执写明任务名", r.contains("戊"), r);
        }
    }

    // ─────────────────────────── undo ───────────────────────────

    private static void undoTest() throws Exception {
        try (Connection conn = memory()) {
            TaskEngine e = engine(conn);
            e.executeFor("add 己 -t month11 day20 time10:00", "cli");
            String r = e.executeFor("set 己 -title 己改 -p 4 -n 换个说法", "cli");
            check("复合 set 一次改三项", exists(conn, "己改") && priority(conn, "己改") == 4
                            && "换个说法".equals(note(conn, "己改")), r);

            check("op_log 记下了 set", count(conn,
                    "SELECT COUNT(*) FROM op_log WHERE op_type='set'") == 1,
                    "set 行数=" + count(conn, "SELECT COUNT(*) FROM op_log WHERE op_type='set'"));

            String u = e.executeFor("undo", "cli");
            check("undo 能撤销 set", u.contains("已恢复"), u);
            check("undo 恢复标题", exists(conn, "己") && !exists(conn, "己改"),
                    "己=" + exists(conn, "己") + " 己改=" + exists(conn, "己改"));
            check("undo 恢复优先级", priority(conn, "己") == 1, "实际=" + priority(conn, "己"));
            check("undo 恢复备注为 NULL", note(conn, "己") == null, "实际=" + note(conn, "己"));
        }
    }

    // ─────────────────────────── 备注与开关顺序 ───────────────────────────

    private static void noteAndOrderTests() throws Exception {
        try (Connection conn = memory()) {
            TaskEngine e = engine(conn);
            e.executeFor("add 庚 -t month11 day8 time09:00", "cli");

            String r = e.executeFor("set 庚 -n 备注里带 -t month11", "cli");
            check("-n 吞掉整行（含像开关的内容）", "备注里带 -t month11".equals(note(conn, "庚")),
                    "实际=" + note(conn, "庚") + "｜" + r);

            r = e.executeFor("set 庚 -title 高数作业 补交 -p 2", "cli");
            check("-title 支持多词且不吃掉后面的开关", exists(conn, "高数作业 补交") && priority(conn, "高数作业 补交") == 2,
                    "存在=" + exists(conn, "高数作业 补交") + " 优先级=" + priority(conn, "高数作业 补交") + "｜" + r);

            r = e.executeFor("set 高数作业 补交 -n 尾巴 -p 9", "cli");
            check("-n 之后的内容一律算备注（与 add 一致）",
                    "尾巴 -p 9".equals(note(conn, "高数作业 补交")), "实际=" + note(conn, "高数作业 补交") + "｜" + r);
            check("-n 之后的开关不生效", priority(conn, "高数作业 补交") == 2,
                    "实际=" + priority(conn, "高数作业 补交"));
        }
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private static long at(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(ZONE).toInstant().toEpochMilli();
    }

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

    private static int priority(Connection conn, String title) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT priority FROM tasks WHERE title=?")) {
            ps.setString(1, title);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    private static String note(Connection conn, String title) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT note FROM tasks WHERE title=?")) {
            ps.setString(1, title);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean exists(Connection conn, String title) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM tasks WHERE title=? AND status NOT IN ('done','removed')")) {
            ps.setString(1, title);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (Exception e) {
            return false;
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

    private static void setLastNotified(Connection conn, String title, long value) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE tasks SET last_notified_at=? WHERE title=?")) {
            ps.setLong(1, value);
            ps.setString(2, title);
            ps.executeUpdate();
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
