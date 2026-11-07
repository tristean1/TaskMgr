import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;


/**
 * SyncCommand —— {@code task sync <源>-><目标>}，把一边的数据库整份搬到另一边（REQ-020）。
 *
 * <p><b>为什么要这个命令</b>：项目要迁到云服务器上独占运行（笔记本只当客户端），
 * 但笔记本仍需要「把服务器的库拉下来」当备份/离线用，以及偶尔「把笔记本临时记的东西并回去」。
 *
 * <p><b>v1 只做方向覆盖，不做合并</b>：箭头左边的库是唯一真相，右边被整份替换。
 * 合并（按 key 并集 + 较新者优先）留到 v2；但即使是覆盖，也配了四道保险：
 * <ol>
 *   <li>源端用 {@code VACUUM INTO} 出**一致快照**（不能直接拷 {@code .db}，WAL 里还有没合并的数据）；</li>
 *   <li>目标端先停守护进程（运行中的进程持有文件句柄与 WAL，直接换文件会读到旧 inode 或损坏库）；</li>
 *   <li>被覆盖的旧库自动留档为 {@code tasks.db.bak-时间戳}；</li>
 *   <li>换完做 {@code PRAGMA integrity_check} 并比对三张表的行数。</li>
 * </ol>
 *
 * <p><b>--dry</b>：只把两边各取一份快照、在本地比对并打印差异，不碰目标库。
 *
 * <p><b>节点名</b>写在 {@code ~/.taskmgr/nodes.properties}：
 * <pre>
 * laptop=local
 * server=aliyun
 * # 可选：远端调用客户端的命令（默认 task）
 * # server.client=task
 * # 可选：远端停止/启动守护进程的命令（默认 "&lt;client&gt; down" / "&lt;client&gt; up"）。
 * # 服务器上由 systemd 托管时必须改成 systemctl，否则 Restart=always 会把它拉起来。
 * # server.stop=systemctl stop taskmgr
 * # server.start=systemctl start taskmgr
 * </pre>
 */
public final class SyncCommand {

    /** 文件名：节点表。 */
    public static final String NODES_FILE = "nodes.properties";

    /** 本机节点的字面量写法。 */
    private static final String LOCAL = "local";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private SyncCommand() {
    }

    // ------------------------------------------------------------------ 入口

    public static int run(String[] argv) {
        try {
            return execute(argv);
        } catch (Exception e) {
            System.out.println("[错误] 同步失败：" + e.getMessage());
            return 1;
        }
    }

    private static int execute(String[] argv) throws Exception {
        boolean dry = false;
        List<String> rest = new ArrayList<>();
        for (int i = 1; i < argv.length; i++) {
            String a = argv[i];
            if (a.equals("--dry") || a.equals("-n")) {
                dry = true;
            } else if (a.startsWith("-")) {
                // 必须显式拦住：否则 `--dey`（--dry 手滑）会被拼进节点名，
                // 报成「未知节点：laptop --dey」，看不出真正的问题。
                System.out.println("[错误] 未知参数：" + a + "（只支持 --dry / -n）");
                return 2;
            } else {
                rest.add(a);
            }
        }
        String spec = String.join(" ", rest).replaceAll("\\s*->\\s*", "->").trim();
        if (spec.isEmpty()) {
            usage();
            return 2;
        }
        int arrow = spec.indexOf("->");
        if (arrow < 0) {
            System.out.println("[错误] 缺少方向。写法示例：task sync server->laptop");
            return 2;
        }
        String srcName = spec.substring(0, arrow).trim();
        String dstName = spec.substring(arrow + 2).trim();
        if (srcName.equalsIgnoreCase(dstName)) {
            System.out.println("[错误] 源和目标是同一个节点：" + srcName);
            return 2;
        }

        Map<String, String> nodes = loadNodes();
        Node src = resolve(nodes, srcName);
        Node dst = resolve(nodes, dstName);
        if (src.local() && dst.local()) {
            System.out.println("[错误] 两个节点都指向本机，没有可同步的对象（检查 " + NODES_FILE + "）");
            return 2;
        }

        Path work = Files.createTempDirectory("taskmgr-sync");
        try {
            System.out.println("[同步] " + src.name() + " -> " + dst.name()
                    + (dry ? "（预演：只比对，不改动）" : ""));

            Path srcSnap = work.resolve("src.db");
            snapshot(src, srcSnap);
            Db srcDb = read(srcSnap);

            if (dry) {
                Path dstSnap = work.resolve("dst.db");
                Db dstDb;
                try {
                    snapshot(dst, dstSnap);
                    dstDb = read(dstSnap);
                } catch (IOException e) {
                    // 目标端还没有库（全新服务器）不是错误：第一次真正同步会直接建库。
                    System.out.println("[同步] 目标端还没有数据库（" + e.getMessage() + "），首次同步会直接建库。");
                    dstDb = new Db(0, 0, 0, 0, new LinkedHashMap<>());
                }
                printDiff(src, srcDb, dst, dstDb);
                System.out.println("[同步] 预演结束，目标库没有被改动。");
                return 0;
            }

            // 1) 停目标端守护进程
            boolean wasRunning = dst.running();
            if (wasRunning) {
                System.out.println("[同步] 停止 " + dst.name() + " 的守护进程…");
                dst.stop();
                if (dst.running()) throw new IOException(dst.name() + " 的守护进程没能停下来，已中止（避免换库过程被并发写入）");
            }

            // 2) 把快照装到目标端（备份 + 替换 + 校验都在那边做，逻辑只有一份）
            System.out.println("[同步] 写入 " + dst.name() + "（源 " + srcDb.tasks() + " 条任务）…");
            try {
                install(dst, srcSnap, work);
            } finally {
                // 安装失败也恢复原来运行的服务；安装器已保证校验失败不覆盖旧库。
                if (wasRunning) {
                    System.out.println("[同步] 重新启动 " + dst.name() + " 的守护进程…");
                    dst.start();
                }
            }
            System.out.println("[同步] 完成。" + src.name() + " -> " + dst.name()
                    + " ｜ tasks " + srcDb.tasks() + " · op_log " + srcDb.opLog()
                    + " · work_session " + srcDb.sessions());
            if (wasRunning) {
                System.out.println("        目标端守护进程已重新启动（" + dst.name() + "）。");
            } else {
                System.out.println("        目标端守护进程原本没在跑，保持不启动。");
            }
            return 0;
        } finally {
            deleteQuietly(work);
        }
    }

    private static void usage() {
        System.out.println("用法：task sync <源>-><目标> [--dry]");
        System.out.println("  例：task sync server->laptop    用服务器的库覆盖本机");
        System.out.println("      task sync laptop->server    用本机的库覆盖服务器");
        System.out.println("      task sync server->laptop --dry   只比对差异，不改动");
        System.out.println("  节点名在 " + DaemonEndpoint.home().resolve(NODES_FILE) + " 里定义。");
    }

    // ------------------------------------------------------------------ 节点

    /** 一个同步端点：{@code ssh} 为空表示本机。 */
    private record Node(String name, String ssh, String client, String stopCmd, String startCmd) {
        boolean local() {
            return ssh == null;
        }

        boolean running() throws Exception {
            if (local()) return DaemonEndpoint.isRunning();
            // 远端用客户端自己的判定（它同样真连一次 socket，不看 PID 文件）。
            // 注意：`task status` 在「没在跑」时退出码是 1，所以这里必须用宽松版，不能当失败。
            String out = sshCmdLenient(ssh, client + " status");
            return out.contains("运行中") || out.contains("已就绪");
        }

        void stop() throws Exception {
            if (local()) {
                stopLocalDaemon();
            } else {
                sshCmd(ssh, stopCmd);
            }
        }

        void start() throws Exception {
            if (local()) {
                TaskClient.spawnDaemon();
                for (int i = 0; i < 100; i++) {
                    if (DaemonEndpoint.isRunning()) return;
                    Thread.sleep(100);
                }
                throw new IOException("本机守护进程启动超时");
            }
            sshCmd(ssh, startCmd);
        }
    }

    private static void stopLocalDaemon() throws Exception {
        if (!DaemonEndpoint.isRunning()) return;
        try (java.nio.channels.SocketChannel ch = DaemonEndpoint.connect()) {
            java.io.InputStream in = new java.io.BufferedInputStream(java.nio.channels.Channels.newInputStream(ch));
            java.io.OutputStream out = new java.io.BufferedOutputStream(java.nio.channels.Channels.newOutputStream(ch));
            DaemonEndpoint.writeLine(out, "{\"id\":1,\"cmd\":\"__shutdown\"}");
            DaemonEndpoint.readLine(in);
        }
        for (int i = 0; i < 50; i++) {
            if (!DaemonEndpoint.isRunning()) return;
            Thread.sleep(100);
        }
    }

    private static Map<String, String> loadNodes() throws IOException {
        Path p = DaemonEndpoint.home().resolve(NODES_FILE);
        Map<String, String> map = new LinkedHashMap<>();
        if (!Files.exists(p)) {
            throw new IOException("找不到节点表 " + p + "。请先写两行，例如：\n"
                    + "    laptop=local\n    server=aliyun");
        }
        String text = Files.readString(p, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            map.put(line.substring(0, eq).trim().toLowerCase(), line.substring(eq + 1).trim());
        }
        return map;
    }

    private static Node resolve(Map<String, String> nodes, String name) {
        String key = name.toLowerCase();
        String target = nodes.get(key);
        if (target == null || target.isEmpty()) {
            throw new IllegalArgumentException("未知节点：" + name + "（在 " + NODES_FILE + " 里没有这一项）");
        }
        if (target.equalsIgnoreCase(LOCAL)) {
            return new Node(name, null, "task", null, null);
        }
        String client = nodes.getOrDefault(key + ".client", "task");
        String stopCmd = nodes.getOrDefault(key + ".stop", client + " down");
        String startCmd = nodes.getOrDefault(key + ".start", client + " up");
        return new Node(name, target, client, stopCmd, startCmd);
    }

    // ------------------------------------------------------------------ 快照 / 写入

    /** 取一致快照：本机直接做，远端让那边的客户端做再 scp 回来。 */
    private static void snapshot(Node node, Path out) throws Exception {
        if (node.local()) {
            snapshotLocal(out);
            return;
        }
        String remote = "/tmp/taskmgr-sync-" + System.nanoTime() + ".db";
        try {
            sshCmd(node.ssh(), node.client() + " __snapshot " + remote);
            scpFrom(node.ssh(), remote, out);
        } finally {
            sshQuietly(node.ssh(), "rm -f " + remote);
        }
    }

    /** 本机快照（{@code __snapshot} 子命令也走这里）。 */
    public static void snapshotLocal(Path out) throws Exception {
        Files.deleteIfExists(out);
        Path db = DaemonEndpoint.dbPath();
        if (!Files.exists(db)) throw new IOException("本机没有数据库：" + db);
        String target = out.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + db.toAbsolutePath()
                + "?mode=ro");
             Statement st = c.createStatement()) {
            st.execute("VACUUM INTO '" + target + "'");
        }
        if (!Files.exists(out)) throw new IOException("快照没有生成：" + out);
    }

    /** 把快照装到节点上：远端先 scp 再让那边的客户端 {@code __install}。 */
    private static void install(Node node, Path snapshot, Path work) throws Exception {
        if (node.local()) {
            installLocal(snapshot);
            return;
        }
        String remote = "/tmp/taskmgr-sync-" + System.nanoTime() + ".db";
        try {
            scpTo(snapshot, node.ssh(), remote);
            String out = sshCmd(node.ssh(), node.client() + " __install " + remote);
            System.out.print(out);
        } finally {
            sshQuietly(node.ssh(), "rm -f " + remote);
        }
    }

    /**
     * 本机安装（{@code __install} 子命令也走这里）：备份旧库 → 换文件 → 清 WAL/SHM → 校验。
     * <p>要求守护进程已停止；否则宁可失败也不要热替换一个正在被写的库。
     */
    public static void installLocal(Path snapshot) throws Exception {
        if (DaemonEndpoint.isRunning()) {
            throw new IOException("本机守护进程还在运行，拒绝热替换数据库（先 task down）");
        }
        if (!Files.exists(snapshot)) throw new IOException("找不到快照：" + snapshot);
        Path db = DaemonEndpoint.dbPath();
        if (Files.exists(db) && Files.isSameFile(snapshot, db)) throw new IOException("快照不能是目标数据库本身");
        Files.createDirectories(db.getParent());
        // 先验证独立暂存副本；损坏快照/复制失败时不能触碰原库。
        Path staged = Files.createTempFile(db.getParent(), "sync-install-", ".db");
        try {
            Db source = read(snapshot);
            Files.copy(snapshot, staged, StandardCopyOption.REPLACE_EXISTING);
            Db installed = read(staged);
            if (!source.equals(installed)) throw new IOException("快照复制前后内容不一致，已中止");
            if (Files.exists(db)) {
                String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                Path bak = db.resolveSibling("tasks.db.bak-" + stamp + "-" + java.util.UUID.randomUUID());
                // 崩溃残留 WAL 也必须纳入备份，不能只搬走主文件然后删 WAL。
                try (Connection old = DriverManager.getConnection("jdbc:sqlite:" + db);
                     Statement st = old.createStatement()) {
                    try (ResultSet rs = st.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
                        if (rs.next() && rs.getInt(1) != 0) throw new IOException("旧库仍被占用，拒绝替换");
                    }
                    st.execute("VACUUM INTO '" + bak.toAbsolutePath().toString().replace("'", "''") + "'");
                }
                read(bak);
                System.out.println("[同步] 旧库已留档：" + bak.getFileName());
            }
            if (DaemonEndpoint.isRunning()) throw new IOException("守护进程已重新启动，拒绝换库");
            Files.deleteIfExists(db.resolveSibling("tasks.db-wal"));
            Files.deleteIfExists(db.resolveSibling("tasks.db-shm"));
            // 不支持原子替换的文件系统直接失败，保持旧库可用。
            Files.move(staged, db, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[同步] 已写入并校验：" + db);
            System.out.print(installed.describe("   "));
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    // ------------------------------------------------------------------ 读库比对

    /** 一个库的概要（行数 + 最近变更 + 任务 id→标题，用来算差异）。 */
    private record Db(long tasks, long opLog, long sessions, long maxUpdated, Map<String, String> titles) {
        String describe(String indent) {
            String when = maxUpdated > 0
                    ? TS.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(maxUpdated), ZoneId.systemDefault()))
                    : "（无）";
            return indent + "tasks " + tasks + " · op_log " + opLog + " · work_session " + sessions
                    + " · 最近变更 " + when + "\n";
        }
    }

    private static Db read(Path db) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + db.toAbsolutePath() + "?mode=ro");
             Statement st = c.createStatement()) {
            String check;
            try (ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
                check = rs.next() ? rs.getString(1) : "?";
            }
            if (!"ok".equalsIgnoreCase(check)) {
                throw new IOException("库完整性校验失败（" + check + "）：" + db);
            }
            long tasks = count(st, "tasks");
            long opLog = count(st, "op_log");
            long sessions = count(st, "work_session");
            long maxUpdated = 0;
            try (ResultSet rs = st.executeQuery("SELECT MAX(updated_at) FROM tasks")) {
                if (rs.next()) maxUpdated = rs.getLong(1);
            }
            Map<String, String> titles = new LinkedHashMap<>();
            try (ResultSet rs = st.executeQuery("SELECT id, title FROM tasks ORDER BY created_at")) {
                while (rs.next()) titles.put(rs.getString(1), rs.getString(2));
            }
            return new Db(tasks, opLog, sessions, maxUpdated, titles);
        }
    }

    private static long count(Statement st, String table) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private static void printDiff(Node src, Db a, Node dst, Db b) {
        System.out.println("  " + src.name() + "（源，将保留）：");
        System.out.print(a.describe("    "));
        System.out.println("  " + dst.name() + "（目标，将被覆盖）：");
        System.out.print(b.describe("    "));

        Set<String> onlyInDst = new TreeSet<>(b.titles().keySet());
        onlyInDst.removeAll(a.titles().keySet());
        Set<String> onlyInSrc = new TreeSet<>(a.titles().keySet());
        onlyInSrc.removeAll(b.titles().keySet());

        if (!onlyInDst.isEmpty()) {
            System.out.println("  ⚠ 只在 " + dst.name() + " 有、" + src.name() + " 没有的任务 "
                    + onlyInDst.size() + " 条（执行后会丢失）：");
            printTitles(b.titles(), onlyInDst);
        }
        if (!onlyInSrc.isEmpty()) {
            System.out.println("  ＋ 只在 " + src.name() + " 有的任务 " + onlyInSrc.size() + " 条（执行后会带过去）：");
            printTitles(a.titles(), onlyInSrc);
        }
        if (onlyInDst.isEmpty() && onlyInSrc.isEmpty()) {
            System.out.println("  两边的任务集合一致（差异只在时间/状态/台账层面）。");
        }
    }

    private static void printTitles(Map<String, String> titles, Set<String> ids) {
        int shown = 0;
        for (String id : ids) {
            if (shown++ >= 10) {
                System.out.println("    …还有 " + (ids.size() - 10) + " 条");
                break;
            }
            System.out.println("    · " + titles.get(id));
        }
    }

    // ------------------------------------------------------------------ 进程

    /** 跑一条远端命令，返回合并后的输出；非 0 退出码抛异常（带输出，方便定位）。 */
    private static String sshCmd(String host, String command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("ssh", host, command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = awaitOutput(p, 180_000L);
        if (!p.waitFor(180, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("远端命令超时：" + command);
        }
        if (p.exitValue() != 0) {
            throw new IOException("远端命令失败（exit=" + p.exitValue() + "）：" + command
                    + (out.isBlank() ? "" : "\n" + out.strip()));
        }
        return out;
    }

    /** 同上，但**不检查退出码**。用于「本来就可能返回非 0」的探测类命令（如 status）。 */
    private static String sshCmdLenient(String host, String command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("ssh", host, command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = awaitOutput(p, 180_000L);
        if (!p.waitFor(180, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("远端命令超时：" + command);
        }
        return out;
    }

    private static void sshQuietly(String host, String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("ssh", host, command);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            awaitOutput(p, 30_000L);
            p.waitFor(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    private static void scpTo(Path local, String host, String remote) throws Exception {
        runProcess("scp", local.toString(), host + ":" + remote);
    }

    private static void scpFrom(String host, String remote, Path local) throws Exception {
        runProcess("scp", host + ":" + remote, local.toString());
    }

    private static void runProcess(String... args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = awaitOutput(p, 180_000L);
        if (!p.waitFor(180, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("命令超时：" + String.join(" ", args));
        }
        if (p.exitValue() != 0) {
            throw new IOException("命令失败（exit=" + p.exitValue() + "）：" + String.join(" ", args)
                    + (out.isBlank() ? "" : "\n" + out.strip()));
        }
    }

    private static String drain(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    /** 同时排空输出与计时；不能先 readAllBytes 再 waitFor（前者可能永远不返回）。 */
    static String awaitOutput(Process process, long timeoutMillis) throws Exception {
        var reader = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sync-process-output"); t.setDaemon(true); return t;
        });
        var output = reader.submit(() -> drain(process.getInputStream()));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) throw new java.util.concurrent.TimeoutException();
            return output.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("同步子进程超时，已终止", e);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            output.cancel(true);
            reader.shutdownNow();
        }
    }

    private static void deleteQuietly(Path dir) {
        try {
            if (!Files.exists(dir)) return;
            try (var s = Files.walk(dir)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        } catch (IOException ignored) {
        }
    }
}
