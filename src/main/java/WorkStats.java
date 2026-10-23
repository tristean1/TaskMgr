import java.sql.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** Read-only interval aggregation. Each day's allocation is the interval/day intersection. */
public final class WorkStats {
    private final Connection conn;
    private final Clock clock;
    public record Policy(Set<String> excludedReasons, long maxSessionMillis) {}
    /**
     * 共享默认策略（2026-10-21，BUG-025 修复）。
     * <p>回执里的「今日累计」与 {@code stats} 必须用同一口径，否则同一天同一活动会出现两个都叫
     * 「累计」的数字。排除：stale / clock_error；超过 8 小时的整段（overlong）不计入样本。
     * <p>注意：sleep 不排除（BUG-024：切点是最后一次心跳，睡眠时段本来就在区间之外）。
     */
    public static final Policy DEFAULT_POLICY =
            new Policy(Set.of("stale", "clock_error"), 8 * 60 * 60 * 1000L);
    public static final class Summary {
        public final String label;
        public final List<Long> samples = new ArrayList<>();
        public final Map<LocalDate, Long> days = new TreeMap<>();
        public final Map<String, Integer> excluded = new TreeMap<>();
        public long runningMillis;
        public int runningCount;
        /** BUG-024：以休眠结束、但**已计入**的区间（切点=最后一次心跳，睡眠时段在区间外）。 */
        public long sleepEndedMillis;
        public int sleepEndedCount;
        Summary(String label) { this.label = label; }
        public long total() { return samples.stream().mapToLong(Long::longValue).sum(); }
        public long median() {
            List<Long> sorted = new ArrayList<>(samples); Collections.sort(sorted);
            int n = sorted.size();
            if (n == 0) return 0;
            return n % 2 == 1 ? sorted.get(n / 2) : sorted.get(n / 2 - 1) + (sorted.get(n / 2) - sorted.get(n / 2 - 1)) / 2;
        }
        public long mean() { return samples.isEmpty() ? 0 : total() / samples.size(); }
    }

    public WorkStats(Connection conn, Clock clock) { this.conn = conn; this.clock = clock; }

    public List<Summary> summarize(String window, String label, Policy policy) throws SQLException {
        long now = clock.millis();
        LocalDate today = LocalDate.now(clock);
        long from = switch (window) {
            case "today" -> today.atStartOfDay(clock.getZone()).toInstant().toEpochMilli();
            case "week" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .atStartOfDay(clock.getZone()).toInstant().toEpochMilli();
            case "all" -> Long.MIN_VALUE;
            default -> throw new IllegalArgumentException("用法：stats today|week|all [活动完整名称]");
        };
        List<WorkTimer.Session> rows = new ArrayList<>();
        String databaseFile = "";
        synchronized (conn) {
            try (var st = conn.createStatement(); var rs = st.executeQuery("PRAGMA database_list")) {
                while (rs.next()) if ("main".equals(rs.getString(2))) databaseFile = rs.getString(3);
            }
            // Private in-memory databases cannot be reopened. Production uses a separate read-only snapshot.
            if (databaseFile == null || databaseFile.isEmpty()) readRows(conn, now, from, label, rows);
        }
        if (databaseFile != null && !databaseFile.isEmpty()) {
            String url = "jdbc:sqlite:" + java.nio.file.Path.of(databaseFile).toUri().toASCIIString() + "?mode=ro";
            try (Connection read = DriverManager.getConnection(url)) {
                readRows(read, now, from, label, rows);
            }
        }
        Map<String, Summary> groups = new TreeMap<>();
        for (WorkTimer.Session row : rows) {
            long end = row.endedAt() == null ? now : row.endedAt();
            long start = Math.max(from, row.startedAt());
            long clippedEnd = Math.min(now, end);
            if (end < from || row.startedAt() > now) continue;
            if (end == from && row.startedAt() < from) continue;
            Summary s = groups.computeIfAbsent(row.label(), Summary::new);
            long duration = Math.max(0, clippedEnd - start);
            if (row.endedAt() == null) { s.runningMillis += duration; s.runningCount++; continue; }
            String exclusion = null;
            if (end < row.startedAt()) exclusion = "clock_error";
            else if (row.reason() != null && policy.excludedReasons().contains(row.reason())) exclusion = row.reason();
            else if (end - row.startedAt() > policy.maxSessionMillis()) exclusion = "overlong";
            if (exclusion != null) { s.excluded.merge(exclusion, 1, Integer::sum); continue; }
            // BUG-024：以休眠结束的区间，时长有效（切点=最后一次心跳）；只标注、不排除。
            if ("sleep".equals(row.reason())) { s.sleepEndedMillis += duration; s.sleepEndedCount++; }
            s.samples.add(duration);
            for (long cursor = start; cursor < clippedEnd;) {
                LocalDate date = Instant.ofEpochMilli(cursor).atZone(clock.getZone()).toLocalDate();
                long boundary = date.plusDays(1).atStartOfDay(clock.getZone()).toInstant().toEpochMilli();
                long until = Math.min(clippedEnd, boundary);
                s.days.merge(date, until - cursor, Long::sum);
                cursor = until;
            }
        }
        return new ArrayList<>(groups.values());
    }

    private static void readRows(Connection read, long now, long from, String label,
                                 List<WorkTimer.Session> rows) throws SQLException {
        try (var ps = read.prepareStatement("SELECT * FROM work_session WHERE started_at<=?"
                + " AND (ended_at IS NULL OR ended_at>=?) AND (?='' OR label=?) ORDER BY started_at,id")) {
            ps.setLong(1, now); ps.setLong(2, from); ps.setString(3, label); ps.setString(4, label);
            try (var rs = ps.executeQuery()) { while (rs.next()) rows.add(WorkTimer.read(rs)); }
        }
    }

    public String report(String window, String label, Policy policy) throws SQLException {
        List<Summary> groups = summarize(window, label, policy);
        long total = groups.stream().mapToLong(Summary::total).sum();
        StringBuilder out = new StringBuilder("【活动时间统计 · " + window + "】\n有效总时长：" + WorkTimer.duration(total));
        if (groups.isEmpty()) return out.append("\n（没有区间记录）").toString();
        for (Summary s : groups) {
            out.append("\n").append(s.label).append(" ｜ ").append(WorkTimer.duration(s.total()))
                    .append(String.format(Locale.ROOT, " ｜ %.1f%%", total == 0 ? 0.0 : s.total() * 100.0 / total))
                    .append(" ｜ n=").append(s.samples.size())
                    .append(" ｜ 中位数 ").append(WorkTimer.duration(s.median()))
                    .append(" ｜ 均值 ").append(WorkTimer.duration(s.mean()));
            if (!s.samples.isEmpty()) out.append("\n  单区间估计参考：")
                    .append(Math.max(1, (s.median() + 59_999) / 60_000)).append(" 分钟（未回填任务估计）");
            out.append("\n  排除区间：").append(s.excluded.values().stream().mapToInt(Integer::intValue).sum())
                    .append("（stale=").append(s.excluded.getOrDefault("stale", 0))
                    .append("，sleep=").append(s.excluded.getOrDefault("sleep", 0))
                    .append("，时钟异常=").append(s.excluded.getOrDefault("clock_error", 0))
                    .append("，超过8小时=").append(s.excluded.getOrDefault("overlong", 0)).append("）");
            if (s.sleepEndedCount > 0) out.append("\n  其中以休眠结束（已计入）：")
                    .append(WorkTimer.duration(s.sleepEndedMillis))
                    .append("（").append(s.sleepEndedCount).append(" 条）");
            if (s.runningCount > 0) out.append(" ｜ 正在计时：").append(WorkTimer.duration(s.runningMillis)).append("（不入样本）");
            for (var day : s.days.entrySet()) out.append("\n  ").append(day.getKey()).append("：").append(WorkTimer.duration(day.getValue()));
        }
        return out.append("\n说明：占比以本次查询有效总时长为分母；样本是窗口内区间重叠时长，并非完整任务耗时。")
                .append("\n异常区间不删除；少量样本及漏记开始会影响估计。")
                .append("以休眠结束的区间时长有效（切点=最后一次心跳，睡眠时段在区间之外），已计入统计。").toString();
    }
}
