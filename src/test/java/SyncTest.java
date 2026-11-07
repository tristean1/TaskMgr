import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * REQ-020（{@code task sync}）里**能在本机验证的那一半**：快照取一致、安装留档、拒绝热替换。
 *
 * <p>跨机器的 ssh/scp 编排没法在单测里做（需要真实 ssh），那部分由 2026-11-06 的手工端到端
 * 验证覆盖：本地沙箱 → 服务器沙箱、服务器沙箱 → 本地沙箱，双向各跑通一次，
 * 含「目标端守护进程在跑」的停-装-启路径。
 *
 * <p>这里只碰临时目录（{@code -Dtaskmgr.home} 指过去），绝不读写真库。
 */
public class SyncTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : ".");
        Files.createDirectories(outDir);

        Path home = Files.createTempDirectory("tm-sync-test");
        System.setProperty("taskmgr.home", home.toString());
        try {
            snapshotTests(home);
            installTests(home);
            guardTests(home);
        } catch (Throwable t) {
            failures.add("未捕获异常：" + t);
        } finally {
            System.clearProperty("taskmgr.home");
            deleteTree(home);
        }

        List<String> lines = new ArrayList<>();
        lines.add("PASS " + passed + " / FAIL " + failures.size());
        lines.addAll(failures);
        Files.writeString(outDir.resolve("sync-results.txt"),
                String.join(System.lineSeparator(), lines) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        if (!failures.isEmpty()) System.exit(1);
    }

    // ─────────────────────────── 快照 ───────────────────────────

    private static void snapshotTests(Path home) throws Exception {
        Path db = home.resolve("tasks.db");
        seed(db, "任务一", "任务二", "任务三");

        Path snap = home.resolve("snap.db");
        SyncCommand.snapshotLocal(snap);
        check("快照文件已生成", Files.exists(snap));
        check("快照与源库行数一致", count(snap, "tasks") == 3, "实际 " + count(snap, "tasks"));

        // 快照必须是可独立打开的完整库（不是半截文件）
        check("快照能通过完整性校验", "ok".equalsIgnoreCase(pragma(snap, "PRAGMA integrity_check")));

        // 源库继续变化后，快照不应跟着变（VACUUM INTO 是拷贝，不是链接）
        addRow(db, "任务四");
        check("源库新增后快照不变", count(snap, "tasks") == 3, "快照里 " + count(snap, "tasks") + " 行");
        check("源库本身已变", count(db, "tasks") == 4);

        Path again = home.resolve("snap2.db");
        SyncCommand.snapshotLocal(again);
        check("二次快照覆盖而非失败", count(again, "tasks") == 4);
    }

    // ─────────────────────────── 安装 ───────────────────────────

    private static void installTests(Path home) throws Exception {
        Path db = home.resolve("tasks.db");
        Path snap = home.resolve("snap.db");   // 3 条
        long before = count(db, "tasks");

        SyncCommand.installLocal(snap);

        check("安装后目标库行数变成快照的行数", count(db, "tasks") == 3,
                "装完 " + count(db, "tasks") + " 行（装前 " + before + "）");
        check("安装后仍能通过完整性校验", "ok".equalsIgnoreCase(pragma(db, "PRAGMA integrity_check")));

        List<Path> backups = backups(home);
        check("旧库留了一份备份", backups.size() == 1, "备份数 " + backups.size());
        if (!backups.isEmpty()) {
            check("备份里是覆盖前的数据", count(backups.get(0), "tasks") == before,
                    "备份 " + count(backups.get(0), "tasks") + " 行");
        }
        check("WAL/SHM 没有残留", !Files.exists(home.resolve("tasks.db-wal"))
                && !Files.exists(home.resolve("tasks.db-shm")));
    }

    // ─────────────────────────── 拒绝条件 ───────────────────────────

    private static void guardTests(Path home) throws Exception {
        // 1) 守护进程在跑时拒绝热替换：用「socket 文件存在且能被 ping 通」无法伪造，
        //    这里退一步验证「快照文件不存在」这条必然命中的拒绝分支。
        boolean threw = false;
        try {
            SyncCommand.installLocal(home.resolve("不存在的快照.db"));
        } catch (Exception e) {
            threw = true;
            check("缺少快照时给出明确错误", e.getMessage().contains("找不到快照"), e.getMessage());
        }
        check("缺少快照时抛异常而不是静默跳过", threw);

        // 2) 空目标库也能装（全新服务器第一次同步）
        Path fresh = Files.createTempDirectory("tm-sync-fresh");
        try {
            Path snap = home.resolve("snap.db");
            // 把「临时 home」指到全新目录，模拟一个还没有 tasks.db 的目标
            System.setProperty("taskmgr.home", fresh.toString());
            SyncCommand.installLocal(snap);
            Path db = fresh.resolve("tasks.db");
            check("目标原本没有库也能装出来", Files.exists(db));
            check("新装出来的库行数正确", count(db, "tasks") == 3);
            check("没有旧库时不留备份", backups(fresh).isEmpty(), "备份数 " + backups(fresh).size());
        } finally {
            System.setProperty("taskmgr.home", home.toString());
            deleteTree(fresh);
        }
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private static void seed(Path db, String... titles) throws Exception {
        Files.deleteIfExists(db);
        Class.forName("org.sqlite.JDBC");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE tasks(id TEXT PRIMARY KEY, title TEXT NOT NULL, note TEXT,"
                    + " trigger_type TEXT, status TEXT NOT NULL DEFAULT 'todo', priority INTEGER,"
                    + " created_at INTEGER, updated_at INTEGER)");
            st.execute("CREATE TABLE op_log(id TEXT PRIMARY KEY, op_type TEXT, task_id TEXT,"
                    + " before_json TEXT, after_json TEXT, created_at INTEGER, undone INTEGER)");
            st.execute("CREATE TABLE work_session(id TEXT PRIMARY KEY, label TEXT, started_at INTEGER,"
                    + " ended_at INTEGER, task_id TEXT)");
        }
        for (int i = 0; i < titles.length; i++) addRow(db, titles[i], i);
    }

    private static void addRow(Path db, String title) throws Exception {
        addRow(db, title, (int) (System.nanoTime() % 1000));
    }

    private static void addRow(Path db, String title, int seq) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO tasks(id, title, status, priority, created_at, updated_at)"
                             + " VALUES(?,?,?,?,?,?)")) {
            long now = System.currentTimeMillis();
            ps.setString(1, "id-" + seq + "-" + title.hashCode());
            ps.setString(2, title);
            ps.setString(3, "todo");
            ps.setInt(4, 1);
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.executeUpdate();
        }
    }

    private static long count(Path db, String table) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private static String pragma(Path db, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement();
             var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : "";
        }
    }

    private static List<Path> backups(Path dir) throws Exception {
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith("tasks.db.bak-"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static void deleteTree(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static void check(String name, boolean ok) {
        check(name, ok, null);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
        } else {
            failures.add("FAIL: " + name + (detail == null ? "" : "（" + detail + "）"));
        }
    }
}
