import java.sql.*;
import java.time.Instant;
import java.util.Scanner;
import java.util.UUID;

/** TaskManager M0 收口版本。命令：add <标题> / list / init / exit。 */
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
            " , priority         INTEGER NOT NULL DEFAULT 0\n" +
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

            Scanner sc = new Scanner(System.in, "UTF-8");
            System.out.println("TaskManager M0. add <标题> / list / init / exit");
            while (true) {
                System.out.print("> ");
                if (!sc.hasNextLine()) break;
                String line = sc.nextLine().trim();
                if (line.isEmpty()) continue;
                String cmd = line.toLowerCase();
                if (cmd.equals("exit") || cmd.equals("quit")) {
                    System.out.println("bye.");
                    break;
                } else if (cmd.equals("list") || cmd.equals("ls")) {
                    listTasks(conn);
                } else if (cmd.equals("init")) {
                    recreateDb(conn);
                } else if (cmd.startsWith("add ")) {
                    String title = line.substring(4).trim();
                    if (title.isEmpty()) {
                        System.out.println("[提示] 标题不能为空。用法：add 明天18点交报告");
                    } else {
                        addTask(conn, title);
                        System.out.println("[OK] 已添加：" + title);
                        listTasks(conn);
                    }
                } else if (cmd.equals("add")) {
                    System.out.println("[提示] 用法：add <标题>");
                } else {
                    System.out.println("[提示] 未知命令：" + line);
                }
            }
        }
    }
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

    /** 清空并重建所有表（危险，仅收口清理测试数据） */
    private static void recreateDb(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS task_note;");
            st.execute("DROP TABLE IF EXISTS tasks;");
            st.execute("DROP TABLE IF EXISTS meta;");
        }
        initSchema(conn);
        System.out.println("[OK] 数据库已重建（schema_version=2）。");
    }

    /** 添加任务。M0 只填必要字段，时间字段由 M1 解析后填充，置 NULL。 */
    private static void addTask(Connection conn, String title) throws SQLException {
        String sql = "INSERT INTO tasks(" +
                "id,title,trigger_type,status,priority," +
                "note,trigger_at,remind_at,estimate_minutes," +
                "recurrence_rule,next_trigger_at,parent_id,last_notified_at," +
                "created_at,updated_at,done_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        long now = Instant.now().toEpochMilli();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, title);
            ps.setString(3, "once");
            ps.setString(4, "todo");
            ps.setInt(5, 0);
            ps.setNull(6, Types.VARCHAR);
            ps.setNull(7, Types.INTEGER);
            ps.setNull(8, Types.INTEGER);
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

    /** 列出所有非 removed 任务 */
    private static void listTasks(Connection conn) throws SQLException {
        String sql = "SELECT title, status, priority, " +
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
                String status = rs.getString("status");
                int priority = rs.getInt("priority");
                String due = rs.getString("due").trim();
                System.out.printf("  %2d. [%s] *%d  %s %s%n",
                        n, status, priority, due.isEmpty() ? "" : ("DDL " + due), title);
            }
            if (n == 0) System.out.println("  （暂无任务，输入 `add 标题` 添加）");
        }
    }
}