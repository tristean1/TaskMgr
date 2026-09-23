import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * TaskEngine —— 可复用的任务命令引擎。
 * <p>
 * 从 {@code Main} 的命令循环中提炼出来，使「控制台」与「QQ 机器人」能复用同一套
 * {@code add / list / init / help} 命令逻辑，且不破坏原有 {@code Main} 的控制台交互。
 * <p>
 * 输入/输出被抽象为 {@code (String line, Consumer<String> out)}：
 * 命令行 out 接 System.out；QQ 机器人 out 接「调用发送接口回复用户」。
 * 数据库沿用现有 {@code tasks.db} 表结构（同一 DDL，幂等建表）。
 */
public class TaskEngine {

    /** 数据库连接（本引擎独占使用，调用方负责 open/close）。 */
    private final Connection conn;
    /** 输出回调；默认打到标准输出。 */
    private Consumer<String> out = System.out::println;

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private static final String DDL_TASKS =
            "CREATE TABLE IF NOT EXISTS tasks (\n" +
            "  id               TEXT PRIMARY KEY\n" +
            " , title            TEXT NOT NULL CHECK (length(trim(title)) > 0)\n" +
            " , note             TEXT\n" +
            " , trigger_type     TEXT    NOT NULL DEFAULT 'once'\n" +
            " , status           TEXT    NOT NULL DEFAULT 'todo'\n" +
            " , priority         INTEGER NOT NULL DEFAULT 1\n" +
            " , trigger_at       INTEGER\n" +
            " , remind_at        INTEGER\n" +
            " , estimate_minutes INTEGER\n" +
            " , recurrence_rule  TEXT\n" +
            " , next_trigger_at  INTEGER\n" +
            " , parent_id        TEXT\n" +
            " , last_notified_at INTEGER\n" +
            " , created_at       INTEGER NOT NULL\n" +
            " , updated_at       INTEGER NOT NULL\n" +
            " , done_at          INTEGER\n" +
            ");";

    private static final String DDL_TASK_NOTE =
            "CREATE TABLE IF NOT EXISTS task_note (\n" +
            "  id         TEXT PRIMARY KEY,\n" +
            "  task_id    TEXT NOT NULL REFERENCES tasks(id),\n" +
            "  content    TEXT NOT NULL,\n" +
            "  created_at INTEGER NOT NULL,\n" +
            "  updated_at INTEGER NOT NULL,\n" +
            "  deleted_at INTEGER\n" +
            ");";

    private static final String DDL_META =
            "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT);";

    public TaskEngine(Connection conn) {
        this.conn = conn;
        try {
            initSchema(conn);
        } catch (SQLException e) {
            throw new IllegalStateException("初始化任务表失败: " + e.getMessage(), e);
        }
    }

    /** 设置输出回调（QQ 机器人或命令行注入）。 */
    public void setOutput(Consumer<String> out) {
        this.out = (out == null) ? System.out::println : out;
    }

    /** 返回当前输出回调。 */
    public Consumer<String> getOutput() {
        return this.out;
    }

    /** 直接执行一行命令（首个词为命令，其余为参数）。返回处理是否完成（false 表示未知命令）。 */
    public boolean execute(String line) {
        if (line == null) { return true; }
        line = line.trim();
        if (line.isEmpty()) return true;
        String[] tokens = line.split("\\s+");
        String cmd = tokens[0].toLowerCase();
        String[] argv = (tokens.length > 1)
                ? java.util.Arrays.copyOfRange(tokens, 1, tokens.length) : new String[0];
        switch (cmd) {
            case "add":       handleAdd(argv);   break;
            case "list":
            case "ls":        handleList(argv);  break;
            case "done":      handleSetStatus(argv, "done");    break;
            case "remove":    handleSetStatus(argv, "removed"); break;
            case "export":    handleExport(argv);      break;
            case "nl":        handleNl(argv);    break;
            case "init":      handleInit();      break;
            case "help":
            case "?":         handleHelp(argv);  break;
            case "exit":
            case "quit":      out.accept("bye."); return true;
            default:          out.accept("[提示] 未知命令：" + cmd + "，输入 help 查看用法"); return false;
        }
        return true;
    }
// ─────────────────────────── add ───────────────────────────
    /** add <标题> [-n 备注] [-p 优先级1-9] [-g once|recurring|ongoing] [-t 触发时刻] [-a 提醒时刻] */
    private void handleAdd(String[] argv) {
        String title = null;
        String note = null;
        String triggerType = "once";
        int priority = 1;
        Long triggerAt = null;
        Long remindAt = null;

        try {
            int i = 0;
            while (i < argv.length) {
                String a = argv[i];
                switch (a) {
                    case "-n":
                        if (i + 1 >= argv.length) { out.accept("[错误] -n 需要备注内容"); return; }
                        note = argv[++i];
                        break;
                    case "-p":
                        if (i + 1 >= argv.length) { out.accept("[错误] -p 需要数字"); return; }
                        try { priority = Integer.parseInt(argv[++i]); }
                        catch (NumberFormatException e) { out.accept("[错误] -p 需要整数，得到：" + argv[i]); return; }
                        break;
                    case "-g":
                        if (i + 1 >= argv.length) { out.accept("[错误] -g 需要触发类型"); return; }
                        triggerType = argv[++i].toLowerCase();
                        break;
                    case "-t":
                        if (i + 1 >= argv.length) { out.accept("[错误] -t 需要触发时刻"); return; }
                        triggerAt = parseTimeToEpochMillis(collectTimeTokens(argv, i));
                        if (triggerAt == null) { out.accept("[提示] add 已放弃（时间无法解析）"); return; }
                        i = consumeTimeTokens(argv, i);
                        break;
                    case "-a":
                        if (i + 1 >= argv.length) { out.accept("[错误] -a 需要提醒时刻"); return; }
                        remindAt = parseTimeToEpochMillis(collectTimeTokens(argv, i));
                        if (remindAt == null) { out.accept("[提示] add 已放弃（时间无法解析）"); return; }
                        i = consumeTimeTokens(argv, i);
                        break;
                    default:
                        if (title == null) title = a; else title = title + " " + a;
                }
                i++;
            }
        } catch (Exception e) {
            out.accept("[错误] add 解析失败: " + e.getMessage());
            return;
        }

        if (title == null || title.isEmpty()) {
            out.accept("用法: add <标题> [-n 备注] [-p 优先级] [-g 类型] [-t 触发] [-a 提醒]");
            return;
        }
        if (!triggerType.equals("once") && !triggerType.equals("recurring") && !triggerType.equals("ongoing")) {
            out.accept("[提示] 触发类型必须为 once / recurring / ongoing，得到: " + triggerType);
            return;
        }
        if (priority < 1 || priority > 9) {
            out.accept("[提示] 优先级 priority 应为 1–9，得到: " + priority);
            return;
        }
        if (remindAt != null && triggerAt != null && remindAt > triggerAt) {
            out.accept("[提示] 提醒时刻 -a 不能在触发时刻 -t 之后");
            return;
        }
        // 同名校验：仅拦截未完成、未删除的任务；已 removed/done 的允许重名创建。
        if (hasActiveTask(title)) {
            out.accept("[提示] 已存在同名任务: \"" + title + "\"，请先 done/remove 后再添加");
            return;
        }
        addTask(title, note, triggerType, priority, triggerAt, remindAt);
        out.accept("[OK] 已添加: \"" + title + "\" (priority=" + priority + ", type=" + triggerType + ")"
                + (triggerAt != null ? ", 触发=" + fmtEpoch(triggerAt) : ", 无触发时刻")
                + (remindAt != null ? ", 提醒=" + fmtEpoch(remindAt) : ""));
        handleList(new String[0]);
    }
// ─────────────────────────── add 的插入助手 ───────────────────────────
    private void addTask(String title, String note, String triggerType, int priority,
                         Long triggerAt, Long remindAt) {
        String sql = "INSERT INTO tasks(" +
                "id,title,note,trigger_type,status,priority," +
                "trigger_at,remind_at,estimate_minutes," +
                "recurrence_rule,next_trigger_at,parent_id,last_notified_at," +
                "created_at,updated_at,done_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        long now = Instant.now().toEpochMilli();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, title);
            if (note != null) ps.setString(3, note); else ps.setNull(3, Types.VARCHAR);
            ps.setString(4, triggerType);
            ps.setString(5, "todo");
            ps.setInt(6, priority);
            if (triggerAt != null) ps.setLong(7, triggerAt); else ps.setNull(7, Types.INTEGER);
            if (remindAt != null) ps.setLong(8, remindAt); else ps.setNull(8, Types.INTEGER);
            ps.setNull(9, Types.INTEGER);
            ps.setNull(10, Types.VARCHAR);
            ps.setNull(11, Types.INTEGER);
            ps.setNull(12, Types.VARCHAR);
            ps.setNull(13, Types.INTEGER);
            ps.setLong(14, now);
            ps.setLong(15, now);
            ps.setNull(16, Types.INTEGER);
            ps.executeUpdate();
        } catch (SQLException e) {
            out.accept("[错误] 插入任务失败: " + e.getMessage());
        }
    }
// ─────────────────────────── done / remove ───────────────────────────
    /**
     * done/remove <任务名>：把指定任务置为 done / removed；不写任务名时作用于
     * 「上次提醒过的任务」（last_notified_at 最晚且未完成未删除）。
     */
    private void handleSetStatus(String[] argv, String newStatus) {
        String title = null;
        String id = null;
        try {
            if (argv.length == 0) {
                // 留空 -> 上次提醒的任务
                String sql = "SELECT id,title FROM tasks "
                        + "WHERE status NOT IN ('done','removed') AND last_notified_at IS NOT NULL "
                        + "ORDER BY last_notified_at DESC LIMIT 1";
                try (PreparedStatement ps = conn.prepareStatement(sql);
                     ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) { id = rs.getString("id"); title = rs.getString("title"); }
                }
                if (id == null) {
                    out.accept("[提示] 没有最近提醒过的任务");
                    return;
                }
            } else {
                title = String.join(" ", argv);
                String sql = "SELECT id, title, status FROM tasks "
                        + "WHERE title=? AND status NOT IN ('done','removed') "
                        + "ORDER BY created_at DESC LIMIT 1";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, title);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) { id = rs.getString("id"); title = rs.getString("title"); }
                    }
                }
                if (id == null) {
                    out.accept("[提示] 未找到未完成任务: \"" + title + "\"");
                    return;
                }
            }

            long now = Instant.now().toEpochMilli();
            String u = "UPDATE tasks SET status=?, done_at=?, updated_at=? WHERE id=?";
            try (PreparedStatement ps = conn.prepareStatement(u)) {
                ps.setString(1, newStatus);
                if ("done".equals(newStatus)) ps.setLong(2, now); else ps.setNull(2, Types.INTEGER);
                ps.setLong(3, now);
                ps.setString(4, id);
                ps.executeUpdate();
            }
            out.accept("[OK] 任务已设为 " + newStatus + ": \"" + title + "\"");
        } catch (SQLException e) {
            out.accept("[错误] " + newStatus + " 失败: " + e.getMessage());
        }
    }

    /** 是否存在未完成未删除的同名任务。 */
    private boolean hasActiveTask(String title) {
        String sql = "SELECT COUNT(*) FROM tasks WHERE title=? AND status NOT IN ('done','removed')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, title);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    // ─────────────────────────── export ───────────────────────────
    /** export [文件路径] —— 导出未来 7 天、未完成未删除的任务为 .ics 日历文件（手机兜底提醒）。 */
    private void handleExport(String[] argv) {
        try {
            String file = (argv.length > 0) ? argv[0] : IcsExporter.DEFAULT_FILE;
            int k = exportIcs(file);
            out.accept("[OK] 已导出 " + k + " 条任务到: " + file
                    + "（未来 7 天，已完成/已移除已排除）");
        } catch (Exception e) {
            out.accept("[错误] 导出失败: " + e.getMessage());
        }
    }

    /** 供自动导出调用的入口（默认文件）。 */
    public int exportIcs() throws Exception {
        return exportIcs(IcsExporter.DEFAULT_FILE);
    }

    /** 导出到指定文件，返回事件条数。 */
    public int exportIcs(String file) throws Exception {
        return new IcsExporter(conn).export(file);
    }

    // ─────────────────────────── nl（S2 临时验证入口）───────────────────────────
    /**
     * nl &lt;一句话&gt; —— <b>临时验证入口（S2）</b>：把自然语言交给 AI 解析并打印结果。
     * <p>
     * ⚠️ <b>这是临时命令</b>：S3 把 AI 接进 {@code handleAdd} 之后，本命令应删除或保留为调试命令。
     * <p>
     * 边界（S2-7）：<b>只打印，不入库</b>，也完全不影响 {@code add} 的既有流程。
     * 失败一律显式报错，不静默。
     */
    private void handleNl(String[] argv) {
        if (argv.length == 0) {
            out.accept("用法: nl <一句话>    例如: nl 下周三前交实验报告 大概4小时");
            return;
        }
        String text = String.join(" ", argv);

        ProviderConfig cfg = ProviderConfig.load();
        if (cfg.isOff()) {
            out.accept("[提示] AI 未启用（" + ProviderConfig.RESOURCE + " 里 mode=off），当前为规则解析模式。");
            return;
        }
        CloudNlParser parser = new CloudNlParser(cfg, java.time.Clock.systemDefaultZone(), out::accept);
        if (!parser.isConfigured()) {
            out.accept("[提示] AI 配置不完整，请检查 " + ProviderConfig.RESOURCE
                    + " （需要 baseurl / apikey / model）。当前: " + cfg);
            return;
        }

        try {
            ParsedTask parsed = parser.parse(text);
            if (parsed == null) {
                out.accept("[提示] AI 未能从这句话里解析出任务。");
                return;
            }
            out.accept("[解析结果] " + parsed);
            // 校验结果一并打印（校验器来自 S1，规则与本命令无关）
            try {
                new ParsedTaskValidator().validate(parsed);
                out.accept("[校验] 通过");
            } catch (ValidationException ve) {
                out.accept("[校验] 未通过：" + ve.getMessage());
            }
            if (!parsed.hasTime()) {
                out.accept("[注意] 未识别到时间，该任务不会有提醒。");
            }
        } catch (NlParseException e) {
            out.accept("[错误] AI 解析失败：" + e.getMessage());
        }
    }

// ─────────────────────────── list ───────────────────────────
    /** list —— 列出所有未删除任务。 */
    private void handleList(String[] argv) {
        String sql = "SELECT title, note, status, priority, trigger_type, " +
                "CASE WHEN trigger_at IS NULL THEN '' " +
                "     ELSE datetime(trigger_at/1000, 'unixepoch', 'localtime') END AS due " +
                "FROM tasks WHERE status != 'removed' ORDER BY created_at ASC";
        int n = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            StringBuilder sb = new StringBuilder("──────── 当前任务 ────────");
            while (rs.next()) {
                n++;
                String title = rs.getString("title");
                String note = rs.getString("note");
                String status = rs.getString("status");
                String type = rs.getString("trigger_type");
                int priority = rs.getInt("priority");
                String due = rs.getString("due").trim();
                sb.append("\n  ").append(n).append(". [").append(status).append("] *").append(priority)
                  .append(" (").append(type).append(")");
                if (!due.isEmpty()) sb.append(" DDL ").append(due);
                sb.append("  ").append(title);
                if (note != null && !note.isEmpty()) sb.append("  ").append(note);
            }
            if (n == 0) sb.append("\n  （暂无任务，发送 `add 标题` 添加）");
            out.accept(sb.toString());
        } catch (SQLException e) {
            out.accept("[错误] list 失败: " + e.getMessage());
        }
    }

    // ─────────────────────────── init ───────────────────────────
    /** init —— 清空并重建所有表（危险）。 */
    private void handleInit() {
        try {
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS task_note;");
                st.execute("DROP TABLE IF EXISTS tasks;");
                st.execute("DROP TABLE IF EXISTS meta;");
            }
            initSchema(conn);
            out.accept("[OK] 数据库已重建（schema_version=2）。");
        } catch (Exception e) {
            out.accept("[错误] init 失败: " + e.getMessage());
        }
    }

    // ─────────────────────────── help ───────────────────────────
    private void handleHelp(String[] argv) {
        out.accept("用法：\n"
            + "  add <标题> [-n 备注] [-p 优先级1-9] [-g once|recurring|ongoing] [-t 触发时刻] [-a 提醒时刻]\n"
            + "  list | ls          —— 列出未删除任务\n"
            + "  done [任务名]      —— 完成任务（留空=上次提醒的任务）\n"
            + "  remove [任务名]    —— 移除任务（留空=上次提醒的任务）\n"
            + "  export [文件]      —— 导出未来7天任务为 .ics（手机日历兜底）\n"
            + "  nl <一句话>        —— [临时·S2] 用 AI 解析一句话并打印结果（不入库）\n"
            + "  init               —— 重建数据库表（危险）\n"
            + "  help | ?           —— 显示本帮助\n"
            + "\n-t / -a 触发时刻支持两种写法：\n"
            + "  1) 绝对日期：year2026 month9 day20 time20:14（缺省组件取当天，缺 time 取 00:00）\n"
            + "  2) 增量时间：T+1h（一小时后）、+30m、+1d\n"
        );
    }
// ─────────────────────────── 建表 ───────────────────────────
    private static void initSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(DDL_TASKS);
            st.execute("CREATE INDEX IF NOT EXISTS idx_tasks_status_time ON tasks(status, trigger_at);");
            st.execute("CREATE INDEX IF NOT EXISTS idx_tasks_next ON tasks(next_trigger_at);");
            st.execute(DDL_TASK_NOTE);
            st.execute(DDL_META);
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO meta(key, value) VALUES('schema_version', '2')")) {
            ps.executeUpdate();
        }
    }

    // ─────────────────────────── 时间解析 ───────────────────────────
    private static Long parseTimeToEpochMillis(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        try { return parseTimeStrict(s); }
        catch (Exception e) { return null; }
    }

    private static Long parseTimeStrict(String s) {
        String inc = s.startsWith("T") ? s.substring(1) : s;
        if (looksLikeIncrement(inc)) {
            return Instant.now().plusMillis(parseIncrementMillis(inc)).toEpochMilli();
        }
        LocalDate date = null;
        LocalTime time = null;
        boolean hasDate = false, hasTime = false, sawAny = false;
        String[] parts = s.split("\\s+");
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("year") || t.startsWith("Year") || t.startsWith("YEAR")) {
                int y = Integer.parseInt(t.substring(4));
                date = safeDate(y, date != null ? date.getMonthValue() : 1, date != null ? date.getDayOfMonth() : 1);
                hasDate = true; sawAny = true;
            } else if (t.startsWith("month") || t.startsWith("Month")) {
                int m = Integer.parseInt(t.substring(5));
                date = safeDate(date != null ? date.getYear() : LocalDate.now().getYear(), m, date != null ? date.getDayOfMonth() : 1);
                hasDate = true; sawAny = true;
            } else if (t.startsWith("day") || t.startsWith("Day")) {
                int d = Integer.parseInt(t.substring(3));
                date = safeDate(date != null ? date.getYear() : LocalDate.now().getYear(), date != null ? date.getMonthValue() : 1, d);
                hasDate = true; sawAny = true;
            } else if (t.startsWith("time") || t.startsWith("Time")) {
                time = LocalTime.parse(t.substring(4));
                hasTime = true; sawAny = true;
            } else {
                try {
                    time = LocalTime.parse(t);
                    hasTime = true; sawAny = true;
                } catch (Exception ex) { return null; }
            }
        }
        if (!sawAny) return null;
        if (!hasDate) date = LocalDate.now();
        if (!hasTime) time = LocalTime.MIDNIGHT;
        return LocalDateTime.of(date, time).atZone(ZONE).toInstant().toEpochMilli();
    }
private static boolean looksLikeIncrement(String s) {
        if (s.isEmpty()) return false;
        String t = s;
        if (t.charAt(0) == '+') t = t.substring(1);
        if (t.isEmpty() || !Character.isDigit(t.charAt(0))) return false;
        return t.matches("[0-9]+([dhms])+.*");
    }

    private static long parseIncrementMillis(String s) {
        String t = s;
        if (t.startsWith("T")) t = t.substring(1);
        if (t.startsWith("+")) t = t.substring(1);
        long total = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)([dhms])").matcher(t);
        while (m.find()) {
            long v = Long.parseLong(m.group(1));
            switch (m.group(2).charAt(0)) {
                case 'd': total += v * 24L * 3600L * 1000L; break;
                case 'h': total += v * 3600L * 1000L; break;
                case 'm': total += v * 60L * 1000L; break;
                case 's': total += v * 1000L; break;
                default: break;
            }
        }
        return total;
    }

    private static LocalDate safeDate(int year, int month, int day) {
        return LocalDate.of(year, month, day);
    }

    /** epoch 毫秒 -> "yyyy-MM-dd HH:mm"（本地时区）。 */
    private static String fmtEpoch(long epochMillis) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZONE).format(Instant.ofEpochMilli(epochMillis));
    }

    private static String collectTimeTokens(String[] argv, int index) {
        StringBuilder sb = new StringBuilder();
        int j = index + 1;
        while (j < argv.length && !argv[j].startsWith("-")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(argv[j]);
            j++;
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static int consumeTimeTokens(String[] argv, int index) {
        int j = index;
        while (j + 1 < argv.length && !argv[j + 1].startsWith("-")) j++;
        return j;
    }
}