import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/** Activity intervals, shared by every input channel. No task is created by timing. */
public final class WorkTimer {
    public static final long SLEEP_GAP_MS = 60_000L;
    private static final long PID = ProcessHandle.current().pid();
    private static final String PROCESS_START = ProcessHandle.current().info().startInstant()
            .map(Instant::toString).orElse("unknown");
    private final Connection conn;
    private final Clock clock;
    private final Consumer<String> events;
    private final BiPredicate<Long, String> alive;

    public record Session(String id, String taskId, String label, long startedAt, Long endedAt,
                          String reason, String source, long updatedAt) {}
    public record Candidate(String taskId, String label) {}

    public WorkTimer(Connection conn, Clock clock, Consumer<String> events) {
        this(conn, clock, events, WorkTimer::processAlive);
    }

    WorkTimer(Connection conn, Clock clock, Consumer<String> events, BiPredicate<Long, String> alive) {
        this.conn = conn;
        this.clock = clock;
        this.events = events;
        this.alive = alive;
    }

    public static void initSchema(Connection conn) throws SQLException {
        try (var st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS work_session (id TEXT PRIMARY KEY,"
                    + " task_id TEXT REFERENCES tasks(id), label TEXT NOT NULL CHECK(length(trim(label))>0),"
                    + " started_at INTEGER NOT NULL, ended_at INTEGER CHECK(ended_at IS NULL OR ended_at>=started_at),"
                    + " end_reason TEXT, source TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_ws_started ON work_session(started_at DESC)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_ws_label ON work_session(label,started_at DESC)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_ws_task ON work_session(task_id,started_at DESC)");
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_ws_one_open ON work_session((1)) WHERE ended_at IS NULL");
            st.execute("CREATE TABLE IF NOT EXISTS work_timer_owner (singleton INTEGER PRIMARY KEY CHECK(singleton=1),"
                    + " pid INTEGER NOT NULL, process_start TEXT NOT NULL)");
        }
    }

    public static String normalize(String text) {
        return text == null ? "" : text.strip().replaceAll("(?U)\\s+", " ");
    }

    public Session current() throws SQLException {
        synchronized (conn) {
            return one("SELECT * FROM work_session WHERE ended_at IS NULL");
        }
    }

    private Session one(String sql) throws SQLException {
        try (var st = conn.createStatement(); var rs = st.executeQuery(sql)) {
            return rs.next() ? read(rs) : null;
        }
    }

    static Session read(ResultSet rs) throws SQLException {
        long end = rs.getLong("ended_at");
        Long ended = rs.wasNull() ? null : end;
        return new Session(rs.getString("id"), rs.getString("task_id"), rs.getString("label"),
                rs.getLong("started_at"), ended, rs.getString("end_reason"),
                rs.getString("source"), rs.getLong("updated_at"));
    }

    public String working(String name, String source) throws SQLException {
        String result = SqlTransaction.run(conn, () -> {
            Session open = current();
            String label = normalize(name);
            if (open != null && (label.isEmpty() || label.equals(open.label()))) return describe(open);
            if (label.isEmpty()) {
                Session last = one("SELECT * FROM work_session ORDER BY started_at DESC,rowid DESC LIMIT 1");
                if (last == null) return "用法：working <活动名>（也可以是任务名）";
                label = last.label();
            }
            List<Candidate> candidates = resolve(label);
            if (candidates.size() > 1) {
                StringBuilder sb = new StringBuilder("[提示] 名称匹配多条，请输入完整名称或列出的任务 UUID：");
                for (Candidate c : candidates) sb.append("\n  ").append(c.label())
                        .append(c.taskId() == null ? "（活动）" : "（任务 " + c.taskId() + "）");
                return sb.toString();
            }
            Candidate selected = candidates.isEmpty() ? new Candidate(null, label) : candidates.get(0);
            long now = clock.millis();
            String prefix = open == null ? "" : close(open, now, "switch") + "\n";
            String id = UUID.randomUUID().toString();
            try (var ps = conn.prepareStatement("INSERT INTO work_session"
                    + "(id,task_id,label,started_at,source,created_at,updated_at) VALUES(?,?,?,?,?,?,?)")) {
                ps.setString(1, id); ps.setString(2, selected.taskId()); ps.setString(3, selected.label());
                ps.setLong(4, now); ps.setString(5, source); ps.setLong(6, now); ps.setLong(7, now);
                ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("INSERT OR REPLACE INTO work_timer_owner VALUES(1,?,?)")) {
                ps.setLong(1, PID); ps.setString(2, PROCESS_START); ps.executeUpdate();
            }
            return prefix + "[计时开始] " + selected.label() + " ｜ " + time(now)
                    + " ｜ 今日累计 " + duration(todayTotal(selected.label(), now));
        });
        if (result.contains("[计时开始]")) event(result);
        return result;
    }

    /** Exact TODO first, then exact historical label, then unique literal substring. */
    private List<Candidate> resolve(String name) throws SQLException {
        List<Candidate> tasks = new ArrayList<>();
        try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT id,title FROM tasks WHERE status='todo' ORDER BY created_at,id")) {
            while (rs.next()) tasks.add(new Candidate(rs.getString(1), normalize(rs.getString(2))));
        }
        List<Candidate> exact = tasks.stream().filter(c -> c.label().equals(name)).toList();
        if (!exact.isEmpty()) return exact;
        List<Candidate> byId = tasks.stream().filter(c -> c.taskId().equals(name)).toList();
        if (!byId.isEmpty()) return byId;
        Set<String> history = new TreeSet<>();
        try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT DISTINCT label FROM work_session")) {
            while (rs.next()) history.add(rs.getString(1));
        }
        if (history.contains(name)) return List.of(new Candidate(null, name));
        List<Candidate> partial = new ArrayList<>();
        Set<String> taskLabels = new HashSet<>();
        for (Candidate c : tasks) if (c.label().contains(name)) { partial.add(c); taskLabels.add(c.label()); }
        for (String label : history) if (label.contains(name) && !taskLabels.contains(label)) partial.add(new Candidate(null, label));
        return partial;
    }

    public String pause() throws SQLException {
        String result = SqlTransaction.run(conn, () -> {
            Session open = current();
            return open == null ? "[提示] 当前没有在计时的活动；working 可继续最近活动。"
                    : close(open, clock.millis(), "pause");
        });
        if (result.startsWith("[计时")) event(result);
        return result;
    }

    /** Caller holds write transaction; task status and closure can commit together. */
    public String close(Session open, long at, String reason) throws SQLException {
        if (at < open.startedAt()) { at = open.startedAt(); reason = "clock_error"; }
        try (var ps = conn.prepareStatement("UPDATE work_session SET ended_at=?,end_reason=?,updated_at=? WHERE id=? AND ended_at IS NULL")) {
            ps.setLong(1, at); ps.setString(2, reason); ps.setLong(3, at); ps.setString(4, open.id());
            if (ps.executeUpdate() != 1) throw new SQLException("计时状态已改变，请重试");
        }
        return "[计时结束] " + open.label() + " ｜ 本次 " + duration(at - open.startedAt())
                + "（" + reason + "）｜ 今日累计 " + duration(todayTotal(open.label(), clock.millis()));
    }

    /** Existing reminder tick calls this; persisted updated_at is the last known live cut-off. */
    public String tick() throws SQLException {
        String result = SqlTransaction.run(conn, () -> {
            Session open = current();
            if (open == null) return "";
            long now = clock.millis();
            boolean ownerAlive = false;
            try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT pid,process_start FROM work_timer_owner WHERE singleton=1")) {
                if (rs.next()) ownerAlive = alive.test(rs.getLong(1), rs.getString(2));
            }
            if (!ownerAlive) {
                return "[提示] 已收尾上次退出遗留的计时；截止到最后心跳。\n"
                        + close(open, Math.min(now, open.updatedAt()), "stale");
            }
            if (now < open.startedAt() || now < open.updatedAt() - 5_000L) {
                return "[提示] 检测到时钟回拨，异常区间按零时长暂停。\n" + close(open, open.startedAt(), "clock_error");
            }
            if (now - open.updatedAt() > SLEEP_GAP_MS) {
                return "[提示] 检测到休眠/心跳中断 " + duration(now - open.updatedAt())
                        + "，计到 " + time(open.updatedAt()) + "，不会自动恢复。\n"
                        + close(open, open.updatedAt(), "sleep");
            }
            try (var ps = conn.prepareStatement("UPDATE work_session SET updated_at=? WHERE id=? AND ended_at IS NULL")) {
                ps.setLong(1, Math.max(now, open.updatedAt())); ps.setString(2, open.id()); ps.executeUpdate();
            }
            return "";
        });
        if (!result.isEmpty()) event(result);
        return result;
    }

    public String shutdown() throws SQLException {
        String result = SqlTransaction.run(conn, () -> {
            Session open = current();
            if (open == null) return "";
            try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT pid,process_start FROM work_timer_owner WHERE singleton=1")) {
                if (!rs.next() || rs.getLong(1) != PID || !PROCESS_START.equals(rs.getString(2))) return "";
            }
            long now = clock.millis();
            boolean gap = now - open.updatedAt() > SLEEP_GAP_MS;
            return close(open, gap ? open.updatedAt() : now, gap ? "sleep" : "manual");
        });
        if (!result.isEmpty()) event(result);
        return result;
    }

    private static boolean processAlive(long pid, String start) {
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
                .map(p -> p.info().startInstant().map(i -> i.toString().equals(start)).orElse(false)).orElse(false);
    }

    /**
     * 今日累计（回执用）。
     * <p>BUG-025（2026-10-21 修复）：原实现不过滤异常区间，与 {@code stats} 的两套口径会分叉
     * （同一天同一活动出现两个都叫「累计」的数字）。现在与 {@link WorkStats#DEFAULT_POLICY} 完全一致：
     * 排除 stale / clock_error，以及整段超过 8 小时的 overlong 区间；运行中的区间照常计入。
     */
    private long todayTotal(String label, long now) throws SQLException {
        long from = Instant.ofEpochMilli(now).atZone(clock.getZone()).toLocalDate()
                .atStartOfDay(clock.getZone()).toInstant().toEpochMilli();
        long total = 0;
        try (var ps = conn.prepareStatement(
                "SELECT started_at,ended_at,end_reason FROM work_session "
                        + "WHERE label=? AND started_at<=? AND (ended_at IS NULL OR ended_at>=?)")) {
            ps.setString(1, label); ps.setLong(2, now); ps.setLong(3, from);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    long startedAt = rs.getLong(1);
                    long end = rs.getLong(2);
                    boolean running = rs.wasNull();
                    String reason = rs.getString(3);
                    if (running) end = now;
                    long duration = Math.max(0, Math.min(now, end) - Math.max(from, startedAt));
                    if (duration == 0) continue;
                    if (!running) {
                        long full = Math.max(0, end - startedAt);
                        if (reason != null && WorkStats.DEFAULT_POLICY.excludedReasons().contains(reason)) continue;
                        if (full > WorkStats.DEFAULT_POLICY.maxSessionMillis()) continue;
                    }
                    total += duration;
                }
            }
        }
        return total;
    }

    public String describe(Session session) {
        return "[计时中] " + session.label() + " ｜ 已 " + duration(clock.millis() - session.startedAt())
                + "（" + time(session.startedAt()) + " 开始）";
    }

    public void event(String text) {
        try { events.accept(text); }
        catch (RuntimeException e) { System.err.println("[计时日志失败] " + e.getMessage()); }
    }

    private String time(long at) { return DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(clock.getZone()).format(Instant.ofEpochMilli(at)); }
    public static String duration(long millis) {
        long minutes = Math.max(0, millis) / 60_000L;
        return minutes < 60 ? minutes + " 分钟" : minutes / 60 + " 小时 " + minutes % 60 + " 分";
    }
}
