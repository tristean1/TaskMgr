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
 * REQ-018（循环任务）回归测试。
 *
 * <p>全部在 {@code :memory:} 库里跑，绝不碰 {@code ~/.taskmgr/tasks.db}。
 * 三组关注点：
 * <ol>
 *   <li><b>纯函数</b>：周期推进（日/周/月、跨月钳日、until 截止、休眠多日后一步跳过）；</li>
 *   <li><b>add 解析</b>：-r/-until 的 6 条错误路径 + 两条成功路径的列值；</li>
 *   <li><b>状态机</b>：markNotified（推进 + 幂等）→ rearmDue（重新武装）→ done → reviveDue（复活）；</li>
 *   <li><b>回归</b>：一次性任务在以上所有路径里行为不变。</li>
 * </ol>
 *
 * <p>中文结果一律写 UTF-8 文件，不走 stdout（AGENTS.md 铁律 3）。
 */
public class RecurrenceTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    /** 首个场景的「现在」：2026-11-01 09:00 +08:00（首次触发 21:00 之前）。 */
    private static final Instant NOW = Instant.parse("2026-11-01T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZONE);

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : ".");
        Files.createDirectories(outDir);
        List<String> lines = new ArrayList<>();
        try {
            advanceTests();
            parseTests();
            stateMachineTests();
            regressionTests();
        } catch (Throwable t) {
            failures.add("未捕获异常：" + t);
        }
        lines.add("PASS " + passed + " / FAIL " + failures.size());
        lines.addAll(failures);
        Files.writeString(outDir.resolve("recurrence-results.txt"),
                String.join(System.lineSeparator(), lines) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        if (!failures.isEmpty()) System.exit(1);
    }

    // ─────────────────────────── 1) 周期推进 ───────────────────────────

    private static void advanceTests() {
        long base = at(2026, 11, 1, 21, 0);

        // ① 每天：过了当天 21:00 → 次日 21:00（时分原样保留）
        check("1d 推进到次日同一时刻",
                eq(Recurrence.nextAfter(base, Recurrence.DAILY, at(2026, 11, 1, 22, 0)),
                        at(2026, 11, 2, 21, 0)), "");
        // ② 恰好等于触发时刻 → 仍要严格大于，取次日
        check("1d 恰在触发时刻也推进一天",
                eq(Recurrence.nextAfter(base, Recurrence.DAILY, base), at(2026, 11, 2, 21, 0)), "");
        // ③ 休眠 5 天后一步跳到下次（不是连加 5 次）
        check("1d 跨多日后直接跳到下一次",
                eq(Recurrence.nextAfter(base, Recurrence.DAILY, at(2026, 11, 6, 12, 0)),
                        at(2026, 11, 6, 21, 0)), "");
        // ④ 每周
        check("1w 推进一周",
                eq(Recurrence.nextAfter(base, Recurrence.WEEKLY, at(2026, 11, 1, 22, 0)),
                        at(2026, 11, 8, 21, 0)), "");
        // ⑤ 每月 31 号：跨 2 月钳到 28，再到 3 月回到 31（不漂移）
        long jan31 = at(2026, 1, 31, 9, 0);
        check("1mo 31 号 → 2 月 28 号",
                eq(Recurrence.nextAfter(jan31, "1mo;dom=31", at(2026, 1, 31, 10, 0)),
                        at(2026, 2, 28, 9, 0)), "");
        check("1mo 28 号之后回到 31 号（不漂移）",
                eq(Recurrence.nextAfter(jan31, "1mo;dom=31", at(2026, 2, 28, 10, 0)),
                        at(2026, 3, 31, 9, 0)), "");
        // ⑥ until 截止
        String capped = Recurrence.buildRule(Recurrence.DAILY, null, at(2026, 11, 3, 0, 0));
        check("until 之内仍推进",
                eq(Recurrence.nextAfter(base, capped, at(2026, 11, 1, 22, 0)),
                        at(2026, 11, 2, 21, 0)), "");
        check("越过 until 返回 null",
                Recurrence.nextAfter(base, capped, at(2026, 11, 2, 22, 0)) == null, "");

        // ⑦ 规则串往返
        String rule = Recurrence.buildRule("1mo", 15, null);
        check("规则串含 dom", "1mo;dom=15".equals(rule), rule);
        check("describe 可读", Recurrence.describe(rule).contains("每月")
                && Recurrence.describe(rule).contains("15"), Recurrence.describe(rule));
        check("非循环描述为空", Recurrence.describe(null).isEmpty(), "");
        check("untilOf 只认 until", Recurrence.untilOf("1d") == null
                && Recurrence.untilOf(capped) == at(2026, 11, 3, 0, 0), "");
    }

    // ─────────────────────────── 2) add 解析 ───────────────────────────

    private static void parseTests() throws Exception {
        try (Connection conn = memory()) {
            TaskEngine e = engine(conn);

            String r = e.executeFor("add 甲 -r 1d", "cli");
            check("无 -t 拒绝", r.contains("-r 需要 -t"), r);

            r = e.executeFor("add 甲 -r 1x -t month11 day1 time21:00", "cli");
            check("非法周期拒绝", r.contains("不支持的循环周期"), r);

            r = e.executeFor("add 甲 -g recurring -t month11 day1 time21:00", "cli");
            check("-g recurring 单写拒绝（老坑：静默无效）", r.contains("必须写 -r"), r);

            r = e.executeFor("add 甲 -r 1d -t month11 day1 time21:00 -a month11 day1 time20:00", "cli");
            check("循环任务拒绝 -a", r.contains("不支持 -a"), r);

            r = e.executeFor("add 甲 -until month11 day2", "cli");
            check("-until 单写拒绝", r.contains("-until 只能和 -r"), r);

            r = e.executeFor("add 甲 -r 1d -t month11 day5 time21:00 -until month11 day1", "cli");
            check("-until 早于首次触发拒绝", r.contains("不能在首次触发时刻之前"), r);

            // 成功：每天
            r = e.executeFor("add 每天晚上提醒 -r 1d -t month11 day1 time21:00", "cli");
            check("每天循环建成", rule(conn, "每天晚上提醒").equals("1d"), rule(conn, "每天晚上提醒"));
            check("类型自动设为 recurring", "recurring".equals(triggerType(conn, "每天晚上提醒")),
                    String.valueOf(triggerType(conn, "每天晚上提醒")));
            check("首次触发时刻落库", eq(triggerAt(conn, "每天晚上提醒"), at(2026, 11, 1, 21, 0)),
                    String.valueOf(triggerAt(conn, "每天晚上提醒")));
            check("next_trigger_at 初始为空", nextTriggerAt(conn, "每天晚上提醒") == null, "");
            check("回执写明循环", r.contains("[循环]") && r.contains("[OK]"), r);

            // 成功：每月（dom 记首次的日）
            e.executeFor("add 月租 -r 1mo -t month11 day15 time09:00", "cli");
            check("每月循环含 dom", "1mo;dom=15".equals(rule(conn, "月租")), rule(conn, "月租"));

            // list 里能看见循环标记
            String list = e.executeFor("list", "cli");
            check("list 显示循环标记", list.contains("[循环]") && list.contains("每天"), list);
        }
    }

    // ─────────────────────────── 3) 状态机 ───────────────────────────

    private static void stateMachineTests() throws Exception {
        try (Connection conn = memory()) {
            TaskEngine e = engine(conn);
            e.executeFor("add 每天晚上提醒 -r 1d -t month11 day1 time21:00", "cli");
            String id = id(conn, "每天晚上提醒");

            // ① 提醒成功：写 last_notified + 推进 trigger_at
            long t1 = at(2026, 11, 1, 21, 0) + 5000;
            check("markNotified 首次写入", Recurrence.markNotified(conn, id, t1, "1d",
                    Long.valueOf(at(2026, 11, 1, 21, 0))), "");
            check("last_notified 已写", lastNotified(conn, id) != null, "");
            check("trigger_at 前进到次日", eq(triggerAt(conn, "每天晚上提醒"), at(2026, 11, 2, 21, 0)),
                    String.valueOf(triggerAt(conn, "每天晚上提醒")));
            check("状态仍为 todo（响过≠完成）", "todo".equals(status(conn, id)), String.valueOf(status(conn, id)));

            // ② 幂等：同一轮再写一次不生效
            check("markNotified 第二次返回 false", !Recurrence.markNotified(conn, id, t1 + 10, "1d",
                    Long.valueOf(at(2026, 11, 2, 21, 0))), "");

            // ③ rearm：到了下一次的触发时刻 → 清空 last_notified，让扫描器重新武装
            long t2 = at(2026, 11, 2, 21, 0) + 10000;
            int rearmed = Recurrence.rearmDue(conn, t2);
            check("rearmDue 清空 last_notified", rearmed == 1 && lastNotified(conn, id) == null,
                    "rearmed=" + rearmed);
            check("rearm 不动 trigger_at", eq(triggerAt(conn, "每天晚上提醒"), at(2026, 11, 2, 21, 0)), "");

            // ④ 再响一次 → 推进到第三天
            check("重新武装后可再次提醒", Recurrence.markNotified(conn, id, t2, "1d",
                    Long.valueOf(at(2026, 11, 2, 21, 0))), "");
            check("第二天推进到第三天", eq(triggerAt(conn, "每天晚上提醒"), at(2026, 11, 3, 21, 0)), "");

            // ⑤ done → 记下「次日 00:00 复活」
            String r = e.executeFor("done 每天晚上提醒", "cli");
            check("done 成功", r.contains("[OK]"), r);
            check("done 后状态", "done".equals(status(conn, id)), String.valueOf(status(conn, id)));
            check("done 后写复活时刻",
                    eq(nextTriggerAt(conn, "每天晚上提醒"),
                            Recurrence.reviveAt(at(2026, 11, 1, 9, 0))),
                    String.valueOf(nextTriggerAt(conn, "每天晚上提醒")));

            // ⑥ revive：过了复活时刻 → 回 TODO，trigger_at 前进，复活列清空
            long t3 = at(2026, 11, 2, 0, 0) + 10000;
            int revived = Recurrence.reviveDue(conn, t3);
            check("reviveDue 复活", revived == 1 && "todo".equals(status(conn, id)), "revived=" + revived);
            check("复活后 next_trigger_at 清空", nextTriggerAt(conn, "每天晚上提醒") == null, "");
            check("复活后 trigger_at 保持未来那次（不被白推一天）",
                    eq(triggerAt(conn, "每天晚上提醒"), at(2026, 11, 3, 21, 0)),
                    String.valueOf(triggerAt(conn, "每天晚上提醒")));
            check("复活后 last_notified 清空", lastNotified(conn, id) == null, "");

            // ⑦ 幂等：再跑一次不该再复活
            check("reviveDue 第二次返回 0", Recurrence.reviveDue(conn, t3) == 0, "");
        }

        // ⑧ until 到期后不再复活
        try (Connection conn = memory()) {
            TaskEngine e = engine(conn);
            String until = "month11 day3 time00:00";
            e.executeFor("add 短期循环 -r 1d -t month11 day1 time21:00 -until " + until, "cli");
            String id = id(conn, "短期循环");
            e.executeFor("done 短期循环", "cli");
            int revived = Recurrence.reviveDue(conn, at(2026, 12, 1, 0, 0));
            check("越过 until 不复活（仍为 done）",
                    "done".equals(status(conn, id)) && revived == 1, "revived=" + revived);
            check("越过 until 后不再重试", nextTriggerAt(conn, "短期循环") == null, "");
        }
    }

    // ─────────────────────────── 4) 一次性任务回归 ───────────────────────────

    private static void regressionTests() throws Exception {
        try (Connection conn = memory()) {
            TaskEngine e = engine(conn);
            e.executeFor("add 一次性 -t month11 day5 time10:00", "cli");
            String id = id(conn, "一次性");
            long before = triggerAt(conn, "一次性");

            check("一次性任务 markNotified 仍写入", Recurrence.markNotified(conn, id,
                    at(2026, 11, 5, 10, 0) + 1000, null, Long.valueOf(before)), "");
            check("一次性任务 trigger_at 不变", eq(triggerAt(conn, "一次性"), before), "");
            check("一次性任务状态不变", "todo".equals(status(conn, id)), String.valueOf(status(conn, id)));
            check("一次性任务 rearmDue 不影响它", Recurrence.rearmDue(conn, at(2026, 11, 9, 0, 0)) == 0, "");
            check("一次性任务 reviveStampFor 为 null", Recurrence.reviveStampFor(null,
                    at(2026, 11, 1, 9, 0)) == null, "");

            e.executeFor("done 一次性", "cli");
            check("一次性任务 done 后不写复活时刻", nextTriggerAt(conn, "一次性") == null, "");
            check("一次性任务不会被复活", Recurrence.reviveDue(conn, at(2026, 12, 1, 0, 0)) == 0, "");
        }
    }

    // ─────────────────────────── 小工具 ───────────────────────────

    private static boolean eq(Long actual, long expected) {
        return actual != null && actual == expected;
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
        } else {
            failures.add("[FAIL] " + name + " ｜ 实际输出：" + detail);
        }
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(ZONE).toInstant().toEpochMilli();
    }

    private static Connection memory() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite::memory:");
    }

    private static TaskEngine engine(Connection conn) {
        return new TaskEngine(conn, CLOCK, ignored -> {});
    }

    private static String id(Connection conn, String title) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM tasks WHERE title=?")) {
            ps.setString(1, title);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static Long triggerAt(Connection conn, String title) {
        return time(conn, title, "trigger_at");
    }

    private static Long nextTriggerAt(Connection conn, String title) {
        return time(conn, title, "next_trigger_at");
    }

    private static Long lastNotified(Connection conn, String id) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT last_notified_at FROM tasks WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                long v = rs.getLong(1);
                return rs.wasNull() ? null : Long.valueOf(v);
            }
        }
    }

    private static Long time(Connection conn, String title, String column) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + column + " FROM tasks WHERE title=?")) {
            ps.setString(1, title);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                long v = rs.getLong(1);
                return rs.wasNull() ? null : Long.valueOf(v);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String rule(Connection conn, String title) {
        return text(conn, title, "recurrence_rule");
    }

    private static String triggerType(Connection conn, String title) {
        return text(conn, title, "trigger_type");
    }

    private static String status(Connection conn, String id) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT status FROM tasks WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(Connection conn, String title, String column) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + column + " FROM tasks WHERE title=?")) {
            ps.setString(1, title);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            return null;
        }
    }
}
