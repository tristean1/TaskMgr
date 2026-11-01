import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 循环任务（REQ-018）的规则解析与状态推进。
 *
 * <h2>三个时刻各司其职（全部复用已有列，不加表不加列）</h2>
 * <table border="1">
 *   <tr><th>列</th><th>循环任务上的含义</th></tr>
 *   <tr><td>{@code trigger_at}</td><td><b>下一次该响的时刻</b>（滚动前进；时分秒即「每天几点」）</td></tr>
 *   <tr><td>{@code next_trigger_at}</td><td><b>复活时刻</b>（仅「已完成」时非 NULL = 次日 00:00；空闲为 NULL）</td></tr>
 *   <tr><td>{@code recurrence_rule}</td><td>规则串：{@code 1d} / {@code 1w} / {@code 1mo[;dom=N][;until=毫秒]}</td></tr>
 * </table>
 *
 * <h2>为什么需要 {@link #rearmDue} 这一步</h2>
 * 两个提醒扫描器都靠 {@code last_notified_at} 判重（非 0 就跳过），SQL 里写死了。
 * 循环任务响过之后这一列就非空，若不清空，第二天永远不会再响。
 * 于是把「下一轮开始」做成一个独立、幂等的动作：当 {@code trigger_at} 已经又到点了
 * 而这一列还非空，说明上一轮已经通知过、现在是新一轮 —— 清空它，让扫描器重新武装。
 *
 * <h2>为什么 {@code done} 之后要靠 {@link #reviveDue} 而不是「提醒时自动完成」</h2>
 * 用户 2026-10-29 裁定：提醒响过但没做完的任务<b>仍然要出现在 next 里</b>，
 * 所以「响过就算完成」被否决。完成只能由用户显式 {@code done} 触发；
 * 而循环任务 {@code done} 之后当天消失、次日 00:00 复活（{@code next_trigger_at}）。
 *
 * <p>先 {@link #reviveDue} 再 {@link #rearmDue}：复活会重置 {@code trigger_at} 与
 * {@code last_notified_at}，放前面可以少一轮空转。
 */
public final class Recurrence {

    public static final String DAILY = "1d";
    public static final String WEEKLY = "1w";
    public static final String MONTHLY = "1mo";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 单次推进的最大步数（1d 时约 5.5 年、1mo 时约 166 年，越过即按 now 重锚）。 */
    private static final int MAX_STEPS = 2000;

    private Recurrence() {}

    // ─────────────────────────── 规则串 ───────────────────────────

    /** 是否循环任务。{@code null} / 空串一律不是（老任务全部落在这里，行为不变）。 */
    public static boolean isRecurring(String rule) {
        return rule != null && !rule.trim().isEmpty();
    }

    /** 周期写法是否合法（供 add 校验用）。 */
    public static boolean isValidPeriod(String period) {
        return DAILY.equals(period) || WEEKLY.equals(period) || MONTHLY.equals(period);
    }

    /**
     * 拼规则串。
     *
     * @param period   {@code 1d} / {@code 1w} / {@code 1mo}
     * @param dom      仅 1mo 用：锚定「每月几号」（首次触发时刻的日）；其余传 null
     * @param untilMs  截止时刻；null = 一直重复
     */
    public static String buildRule(String period, Integer dom, Long untilMs) {
        StringBuilder sb = new StringBuilder(period);
        if (MONTHLY.equals(period) && dom != null) sb.append(";dom=").append(dom);
        if (untilMs != null) sb.append(";until=").append(untilMs);
        return sb.toString();
    }

    /** 取规则里的 {@code until}；没有则 null。 */
    public static Long untilOf(String rule) {
        if (!isRecurring(rule)) return null;
        for (String part : rule.split(";")) {
            if (part.startsWith("until=")) {
                try {
                    return Long.parseLong(part.substring("until=".length()).trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** 取规则里的周期（非法规则返回 null）。 */
    static String periodOf(String rule) {
        if (!isRecurring(rule)) return null;
        String first = rule.split(";")[0].trim();
        return isValidPeriod(first) ? first : null;
    }

    /** 取 {@code dom}；没写则 null。 */
    static Integer domOf(String rule) {
        if (!isRecurring(rule)) return null;
        for (String part : rule.split(";")) {
            if (part.startsWith("dom=")) {
                try {
                    return Integer.parseInt(part.substring("dom=".length()).trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** 中文描述，给 {@code list} / 回执用（不暴露内部串）。 */
    public static String describe(String rule) {
        String period = periodOf(rule);
        if (period == null) return "";
        StringBuilder sb = new StringBuilder();
        if (DAILY.equals(period)) sb.append("每天");
        else if (WEEKLY.equals(period)) sb.append("每周");
        else {
            Integer dom = domOf(rule);
            sb.append("每月").append(dom == null ? "" : dom + " 号");
        }
        Long until = untilOf(rule);
        if (until != null) sb.append(" · 至 ").append(fmtDate(until));
        return sb.toString();
    }

    // ─────────────────────────── 时刻推进 ───────────────────────────

    /**
     * 从 {@code triggerAt} 起按周期推进，返回<b>严格大于 {@code now}</b> 的下一次。
     *
     * <p>用日历运算而不是固定毫秒：{@code 1d} 是「次日同一时分」，
     * 不是「+86400000ms」——后者跨夏令时/闰秒会漂移。
     * {@code 1mo} 每次从原始 {@code triggerAt} 的月数上重算并把日钳到当月最后一天，
     * 因此 {@code 1-31 → 2-28 → 3-31} 不会一路掉到 28 号。
     *
     * @return 下一次的时刻；{@code null} 表示序列已结束（越过 {@code until} 或规则非法）
     */
    public static Long nextAfter(long triggerAt, String rule, long now) {
        String period = periodOf(rule);
        if (period == null) return null;
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime base = Instant.ofEpochMilli(triggerAt).atZone(zone);
        Long until = untilOf(rule);

        ZonedDateTime candidate = null;
        for (int k = 1; k <= MAX_STEPS; k++) {
            candidate = advance(base, period, k, domOf(rule));
            if (candidate.toInstant().toEpochMilli() > now) break;
            candidate = null;
        }
        if (candidate == null) {
            // 步数越界（几十年没跑过）：直接从 now 重锚，保证还能继续响
            candidate = advance(Instant.ofEpochMilli(now).atZone(zone), period, 1, domOf(rule));
        }
        long next = candidate.toInstant().toEpochMilli();
        if (until != null && next > until) return null;
        return next;
    }

    /** 第 {@code k} 个周期后的时刻（从 {@code base} 数，不是从上一次结果累加）。 */
    private static ZonedDateTime advance(ZonedDateTime base, String period, int k, Integer dom) {
        if (DAILY.equals(period)) return base.plusDays(k);
        if (WEEKLY.equals(period)) return base.plusWeeks(k);
        ZonedDateTime shifted = base.plusMonths(k);
        int day = dom != null ? dom : base.getDayOfMonth();
        int last = shifted.toLocalDate().lengthOfMonth();
        return shifted.withDayOfMonth(Math.min(day, last));
    }

    /** 复活时刻：次日 00:00（本地时区）。 */
    public static long reviveAt(long now) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate tomorrow = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1);
        return LocalDateTime.of(tomorrow, java.time.LocalTime.MIDNIGHT).atZone(zone).toInstant().toEpochMilli();
    }

    // ─────────────────────────── 数据库推进（扫描器前置） ───────────────────────────

    /**
     * 重新武装：上一轮已通知过、而 {@code trigger_at} 又到点了 ⇒ 清空 {@code last_notified_at}。
     *
     * <p>这是「循环任务每天都能再响」的唯一机制。幂等：条件 UPDATE 带
     * {@code last_notified_at IS NOT NULL}，多进程同时跑也只会清一次。
     *
     * @return 影响行数
     */
    public static int rearmDue(Connection conn, long now) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM tasks WHERE status='todo' AND last_notified_at IS NOT NULL "
                        + "AND trigger_at IS NOT NULL AND trigger_at<=? "
                        + "AND recurrence_rule IS NOT NULL AND recurrence_rule<>''")) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getString(1));
            }
        }
        int n = 0;
        for (String id : ids) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE tasks SET last_notified_at=NULL "
                            + "WHERE id=? AND status='todo' AND last_notified_at IS NOT NULL "
                            + "AND trigger_at IS NOT NULL AND trigger_at<=?")) {
                ps.setString(1, id);
                ps.setLong(2, now);
                n += ps.executeUpdate();
            }
        }
        return n;
    }

    /**
     * 复活：已完成且到了 {@code next_trigger_at} 的循环任务 → 回到 TODO，并推进到下一次。
     *
     * <p>若序列已结束（越过 {@code -until}），只清空 {@code next_trigger_at}，让它保持已完成，
     * 不再每天重试。
     *
     * @return 复活的行数
     */
    public static int reviveDue(Connection conn, long now) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, trigger_at, recurrence_rule FROM tasks "
                        + "WHERE status='done' AND next_trigger_at IS NOT NULL AND next_trigger_at<=?")) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3)});
                }
            }
        }
        int n = 0;
        for (String[] row : rows) {
            String id = row[0];
            Long triggerAt = row[1] == null ? null : Long.valueOf(row[1]);
            String rule = row[2];
            // 若下一次的时刻本来就还在未来（提前完成的情况），保持不动；
            // 只有已经过期才往前推 —— 否则会把「明天该响的」白推进一天。
            Long next;
            if (triggerAt != null && triggerAt > now) {
                next = triggerAt;
            } else {
                next = triggerAt == null ? null : nextAfter(triggerAt, rule, now);
            }
            if (next == null) {
                // 序列已结束：停在已完成状态，别再每天重试
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE tasks SET next_trigger_at=NULL WHERE id=? AND status='done'")) {
                    ps.setString(1, id);
                    n += ps.executeUpdate();
                }
                continue;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE tasks SET status='todo', done_at=NULL, next_trigger_at=NULL, "
                            + "trigger_at=?, last_notified_at=NULL, updated_at=? "
                            + "WHERE id=? AND status='done' AND next_trigger_at IS NOT NULL")) {
                ps.setLong(1, next);
                ps.setLong(2, now);
                ps.setString(3, id);
                n += ps.executeUpdate();
            }
        }
        return n;
    }

    /**
     * 提醒成功的唯一写入口（两个扫描器共用），顺带把循环任务的 {@code trigger_at} 推到下一次。
     *
     * <p>条件 UPDATE 里的 {@code last_notified_at IS NULL} 保证「两个进程同时扫到同一条」时
     * 只有一个真正写入（另一个影响 0 行）。
     *
     * @param rule      本行的 {@code recurrence_rule}（非循环传 null）
     * @param triggerAt 本行的 {@code trigger_at}（用于算下一次）
     * @return true = 本次由我写入；false = 已被别人写过 / 任务已不是 TODO
     */
    public static boolean markNotified(Connection conn, String id, long now,
                                       String rule, Long triggerAt) throws SQLException {
        Long next = (isRecurring(rule) && triggerAt != null) ? nextAfter(triggerAt, rule, now) : null;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE tasks SET last_notified_at=?, updated_at=?, "
                        + "trigger_at = CASE WHEN ? IS NULL THEN trigger_at ELSE ? END "
                        + "WHERE id=? AND last_notified_at IS NULL AND status='todo'")) {
            ps.setLong(1, now);
            ps.setLong(2, now);
            if (next == null) {
                ps.setNull(3, java.sql.Types.INTEGER);
                ps.setNull(4, java.sql.Types.INTEGER);
            } else {
                ps.setLong(3, next);
                ps.setLong(4, next);
            }
            ps.setString(5, id);
            return ps.executeUpdate() == 1;
        }
    }

    /** 已完成的循环任务写「次日 00:00 复活」；非循环返回 null（调用方写 NULL）。 */
    public static Long reviveStampFor(String rule, long now) {
        return isRecurring(rule) ? Long.valueOf(reviveAt(now)) : null;
    }

    private static String fmtDate(long ms) {
        return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().format(DATE);
    }
}
