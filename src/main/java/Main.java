import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;

/**
 * TaskManager — 效率工具（任务管理 + DDL 提醒）
 * <p>
 * 每种命令一个独立函数，命令行循环按命令名分发。
 * <p>
 * 命令：
 *   add <标题> [-n 备注] [-p 优先级] [-g 触发类型] [-t 触发时刻] [-a 提醒时刻]
 *   list | ls                    列出未删除任务
 *   init                         重建表（危险）
 *   help                         帮助
 *   exit | quit                  退出
 * <p>
 * add 的时间参数支持两种写法：
 *   1) 绝对日期：year2026 month9 day20 time20:14 （缺省组件取当天，缺 time 取 00:00）
 *   2) 增量时间：T+1h / +30m / +1d （基准为当前时刻；T+1h = 一小时后触发）
 */
public class Main {

    private static String dbPath() {
        return System.getProperty("user.home")
                + java.io.File.separator + ".taskmgr"
                + java.io.File.separator + "tasks.db";
    }

    private static final String DDL_TASKS =
            "CREATE TABLE IF NOT EXISTS tasks (\n" +
            "  id               TEXT PRIMARY KEY\n" +
            " , title            TEXT NOT NULL CHECK (length(trim(title)) > 0)\n" +
            " , note             TEXT\n" +
            " , trigger_type     TEXT    NOT NULL DEFAULT 'once'\n" +
            " , status           TEXT    NOT NULL DEFAULT 'todo'\n" +
            " , priority         INTEGER NOT NULL DEFAULT 5\n" +
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

    private static final String SQL_INDEX_STATUS =
            "CREATE INDEX IF NOT EXISTS idx_tasks_status_time ON tasks(status, trigger_at);";
    private static final String SQL_INDEX_NEXT =
            "CREATE INDEX IF NOT EXISTS idx_tasks_next ON tasks(next_trigger_at);";

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

    /** 系统时区，用于把本地时间/日期转成 epoch 毫秒。 */
    private static final ZoneId ZONE = ZoneId.systemDefault();
public static void main(String[] args) throws Exception {
        var dbFile = new java.io.File(dbPath());
        if (dbFile.getParentFile() != null) dbFile.getParentFile().mkdirs();
        System.out.println("[DB] " + dbFile.getAbsolutePath());

        Class.forName("org.sqlite.JDBC");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath())) {
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL;");
                st.execute("PRAGMA busy_timeout=3000;");
            }
            initSchema(conn);

            // 用系统/控制台默认编码读取输入（Java 17 下默认编码跟随控制台代码页），
            // 不要写死成 "UTF-8"，否则在 GBK(936) 的 Windows 控制台输入中文会变乱码/问号。
            Scanner sc = new Scanner(System.in);
            System.out.println("TaskManager. 输入 help 查看用法");
            while (true) {
                System.out.print("> ");
                if (!sc.hasNextLine()) break;
                String line = sc.nextLine().trim();
                if (line.isEmpty()) continue;
                // 拆分：首词为命令，其余为参数
                String[] tokens = line.split("\\s+");
                String cmd = tokens[0].toLowerCase();
                String[] argv = (tokens.length > 1)
                        ? java.util.Arrays.copyOfRange(tokens, 1, tokens.length) : new String[0];

                switch (cmd) {
                    case "add":
                        handleAdd(conn, argv);
                        break;
                    case "list":
                    case "ls":
                        handleList(conn, argv);
                        break;
                    case "init":
                        handleInit(conn);
                        break;
                    case "help":
                    case "?":
                        handleHelp(argv);
                        break;
                    case "exit":
                    case "quit":
                        System.out.println("bye.");
                        return;
                    default:
                        System.out.println("[提示] 未知命令：" + cmd + "，输入 help 查看用法");
                }
            }
        }
    }
// ─────────────────────────── 命令：add ───────────────────────────
    /**
     * add <标题> [-n 备注] [-p 优先级] [-g 触发类型] [-t 触发时刻] [-a 提醒时刻]
     * 默认：trigger_type=once, status=todo, priority=5。
     */
    private static void handleAdd(Connection conn, String[] argv) throws SQLException {
        // title 为第 0 个位置参数（不含开关的部分）
        String title = null;
        String note = null;
        String triggerType = "once";
        int priority = 5;
        Long triggerAt = null;
        Long remindAt = null;

        int i = 0;
        while (i < argv.length) {
            String a = argv[i];
            switch (a) {
                case "-n":
                    if (i + 1 >= argv.length) { System.out.println("[错误] -n 需要备注内容"); return; }
                    note = argv[++i];
                    break;
                case "-p":
                    if (i + 1 >= argv.length) { System.out.println("[错误] -p 需要数字"); return; }
                    try {
                        priority = Integer.parseInt(argv[++i]);
                    } catch (NumberFormatException e) {
                        System.out.println("[错误] -p 需要整数，得到：" + argv[i]);
                        return;
                    }
                    break;
                case "-g":
                    if (i + 1 >= argv.length) { System.out.println("[错误] -g 需要触发类型"); return; }
                    triggerType = argv[++i].toLowerCase();
                    break;
                case "-t":
                    if (i + 1 >= argv.length) { System.out.println("[错误] -t 需要触发时刻"); return; }
                    triggerAt = parseTimeToEpochMillis(collectTimeTokens(argv, i));
                    if (triggerAt == null) { System.out.println("[提示] add 已放弃（时间无法解析）"); return; }
                    i = consumeTimeTokens(argv, i);
                    break;
                case "-a":
                    if (i + 1 >= argv.length) { System.out.println("[错误] -a 需要提醒时刻"); return; }
                    remindAt = parseTimeToEpochMillis(collectTimeTokens(argv, i));
                    if (remindAt == null) { System.out.println("[提示] add 已放弃（时间无法解析）"); return; }
                    i = consumeTimeTokens(argv, i);
                    break;
                default:
                    // 非开关 -> 作为标题（只取第一个非空 token；多个 token 拼接）
                    if (title == null) title = a;
                    else title = title + " " + a;
            }
            i++;
        }

        if (title == null || title.isEmpty()) {
            System.out.println("[提示] 用法: add <标题> [-n 备注] [-p 优先级] [-g 类型] [-t 触发] [-a 提醒]");
            return;
        }
        if (!triggerType.equals("once") && !triggerType.equals("recurring") && !triggerType.equals("ongoing")) {
            System.out.println("[提示] 字段触发类型 trigger_type 必须为 once / recurring / ongoing，得到: " + triggerType);
            return;
        }
        if (priority < 0 || priority > 5) {
            System.out.println("[提示] 优先级 priority 应为 0–5，得到: " + priority);
            return;
        }
        if (remindAt != null && triggerAt != null && remindAt > triggerAt) {
            System.out.println("[提示] 提醒时刻 -a 不能在触发时刻 -t 之后");
            return;
        }

        addTask(conn, title, note, triggerType, priority, triggerAt, remindAt);
        System.out.println("[OK] 已添加: \"" + title + "\" (priority=" + priority + ", type=" + triggerType + ")"
                + (triggerAt != null ? ", 触发=" + fmtEpoch(triggerAt) : ", 无触发时刻")
                + (remindAt != null ? ", 提醒=" + fmtEpoch(remindAt) : ""));
        handleList(conn, new String[0]);
    }
/** 插入任务；时间字段为 epoch 毫秒，可空。id 随机 UUID。 */
    private static void addTask(Connection conn, String title, String note,
                                String triggerType, int priority, Long triggerAt, Long remindAt)
            throws SQLException {
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
        }
    }

    // ─────────────────────────── 命令：list ───────────────────────────
    /** list —— 列出所有未删除任务。 */
    private static void handleList(Connection conn, String[] argv) throws SQLException {
        String sql = "SELECT title, note, status, priority, trigger_type, " +
                "CASE WHEN trigger_at IS NULL THEN '' " +
                "     ELSE datetime(trigger_at/1000, 'unixepoch', 'localtime') END AS due " +
                "FROM tasks WHERE status != 'removed' ORDER BY created_at ASC";
        int n = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            System.out.println("──────── 当前任务 ────────");
            while (rs.next()) {
                n++;
                String title = rs.getString("title");
                String note = rs.getString("note");
                String status = rs.getString("status");
                String type = rs.getString("trigger_type");
                int priority = rs.getInt("priority");
                String due = rs.getString("due").trim();
                StringBuilder sb = new StringBuilder();
                sb.append("  ").append(n).append(". [").append(status).append("] *").append(priority)
                  .append(" (").append(type).append(")");
                if (!due.isEmpty()) sb.append(" DDL ").append(due);
                sb.append("  ").append(title);
                if (note != null && !note.isEmpty()) sb.append("  ").append(note);
                System.out.println(sb);
            }
            if (n == 0) System.out.println("  （暂无任务，输入 `add 标题` 添加）");
        }
    }
// ─────────────────────────── 命令：init ───────────────────────────
    /** init —— 清空并重建所有表（危险操作）。 */
    private static void handleInit(Connection conn) {
        try {
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS task_note;");
                st.execute("DROP TABLE IF EXISTS tasks;");
                st.execute("DROP TABLE IF EXISTS meta;");
            }
            initSchema(conn);
            System.out.println("[OK] 数据库已重建（schema_version=2）。");
        } catch (SQLException e) {
            System.out.println("[错误] init 失败: " + e.getMessage());
        }
    }

    // ─────────────────────────── 命令：help ───────────────────────────
    /** help —— 打印用法。 */
    private static void handleHelp(String[] argv) {
        System.out.println("用法：");
        System.out.println("  add <标题> [-n 备注] [-p 优先级0-5] [-g once|recurring|ongoing] [-t 触发时刻] [-a 提醒时刻]");
        System.out.println("  list | ls          —— 列出未删除任务");
        System.out.println("  init               —— 重建数据库表（危险）");
        System.out.println("  help | ?           —— 显示本帮助");
        System.out.println("  exit | quit        —— 退出");
        System.out.println();
        System.out.println("-t / -a 触发时刻支持两种写法：");
        System.out.println("  1) 绝对日期：year2026 month9 day20 time20:14  （缺省组件取当天，缺 time 取 00:00）");
        System.out.println("  2) 增量时间：T+1h（一小时后）、+30m、+1d    （基准为当前时刻）");
    }

    // ─────────────────────────── 建表 ───────────────────────────
    /** 建表（幂等）+ 写入 schema_version=2 */
    private static void initSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(DDL_TASKS);
            st.execute(SQL_INDEX_STATUS);
            st.execute(SQL_INDEX_NEXT);
            st.execute(DDL_TASK_NOTE);
            st.execute(DDL_META);
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO meta(key, value) VALUES('schema_version', '2')")) {
            ps.executeUpdate();
        }
    }
// ─────────────────────────── 时间解析 ───────────────────────────
    /**
     * 把用户输入的触发时刻/提醒时刻解析成 epoch 毫秒。
     * <p>支持两种写法：
     * <ol>
     *   <li>绝对日期组件：形如 "year2026 month9 day20 time20:14"（也可用一次调用拼接）
     *       —— 单独传给本方法的通常是一个字符串。若含分号分隔多个组件，则整体为一个 token。</li>
     *   <li>增量："+30m"、"+1h"、"+1d"、"+1w"、也可以再加 "T" 前缀（T+1h）。
     *       —— 基准为当前时刻；带 "T" 前缀等价于纯增量（T+1h = 一小时后）。</li>
     * </ol>
     * @return epoch 毫秒；无法解析返回 null。
     */
    private static Long parseTimeToEpochMillis(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        try {
            return parseTimeStrict(s);
        } catch (Exception e) {
            System.out.println("[错误] 无法解析时间: \"" + s + "\" —— " + e.getMessage());
            return null;
        }
    }

    /** parseTimeToEpochMillis 的实现体，异常向上抛由调用方捕获。 */
    private static Long parseTimeStrict(String s) {
        // 情况一：命令里真正的增量 "( +|T+ )(数字)(d|h|m|s)"
        // 形如 +30m、+1h、T+1h、1d、+2h30m
        String inc = s.startsWith("T") ? s.substring(1) : s;
        if (looksLikeIncrement(inc)) {
            return Instant.now().plusMillis(parseIncrementMillis(inc)).toEpochMilli();
        }

        // 情况二：绝对日期组件，形如 "2026 month9 day20 time20:14"
        // 也可能整体用 "-" 分隔，例如 "2026-09-20 20:14"或 "2026-9-20T20:14"
        LocalDate date = null;
        LocalTime time = null;
        boolean hasDate = false;
        boolean hasTime = false;

        // 先按空格拆出 token（year.. / month.. / day.. / time..）
        boolean sawAny = false;
        boolean sawTime = false;
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
                time = LocalTime.parse(t.substring(4)); // hh:mm
                hasTime = true; sawAny = true; sawTime = true;
            } else {
                // 其它 token：若像 "20:14" 视为 time，否则视为非法
                try {
                    time = LocalTime.parse(t);
                    hasTime = true; sawAny = true; sawTime = true;
                } catch (Exception ex) {
                    System.out.println("[时间] 未知组件: " + t);
                    return null;
                }
            }
        }

        // 若没有任何组件，说明是纯文本时间？不支持，回退报错
        if (!sawAny) {
            System.out.println("[时间] 无法识别的时间格式: " + s);
            return null;
        }
        // 日期缺省 -> 今天
        if (!hasDate) date = LocalDate.now();
        // 时间缺省 -> 00:00
        if (!hasTime) time = LocalTime.MIDNIGHT;
        // 组合
        LocalDateTime ldt = LocalDateTime.of(date, time);
        return ldt.atZone(ZONE).toInstant().toEpochMilli();
    }
/** 判断字符串是否为增量表达式：可选 T 前缀 + 数字 + (d|h|m|s)，如 +30m、+1h、T+2h、1d、+1h30m。 */
    private static boolean looksLikeIncrement(String s) {
        if (s.isEmpty()) return false;
        String t = s;
        if (t.charAt(0) == '+') t = t.substring(1);
        if (t.isEmpty() || !Character.isDigit(t.charAt(0))) return false;
        // 剩余部分必须全由 数字 + 单位 组合构成
        return t.matches("[0-9]+([dhms])+.*");
    }

    /** 把 "+2h30m" 之类的增量解析成毫秒。Base=当前时刻。 */
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

    /** 构造合法的 LocalDate；非法返回 null（由上层捕获）。 */
    private static LocalDate safeDate(int year, int month, int day) {
        return LocalDate.of(year, month, day);
    }

    /** 把 epoch 毫秒格式化成 "yyyy-MM-dd HH:mm"（本地时区），用于打印。 */
    private static String fmtEpoch(long epochMillis) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZONE).format(Instant.ofEpochMilli(epochMillis));
    }

    /**
     * 收集 -t / -a 之后的所有连续时间 token（直到遇到下一个以 "-" 开头的开关或数组结束），
     * 用 " " 拼接成一个字符串返回。
     * @return 拼接好的时间字符串；若后面没有任何 token 返回 null。
     */
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

    /** 返回被 -t / -a 消费掉的最后一个 token 的下标（含自身），调用方把 i 赋为其返回值即可跳过。 */
    private static int consumeTimeTokens(String[] argv, int index) {
        int j = index;
        while (j + 1 < argv.length && !argv[j + 1].startsWith("-")) j++;
        return j;
    }
}