import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Daemon —— 常驻后台进程（REQ-019 第 1 期）。
 *
 * <p><b>为什么要它</b>：今天 {@code Main} 就是进程本体，关掉命令行窗口＝提醒全停；
 * 而且 {@code Main}/{@code QqBot}/{@code ReminderMain} 各自起一套提醒扫描器（BUG-007 的同源病）。
 * 守护进程成为<b>唯一写者</b>后，SQLite 回到单写者语义，提醒扫描也只剩一条路径。
 *
 * <p><b>它只是一个「命令执行器 + 事件源」</b>：
 * <ul>
 *   <li>{@code {"id":N,"cmd":"..."}} → {@code {"id":N,"out":"...","exit":0}} 回执流（有始有终、有序）；</li>
 *   <li>{@code {"event":"msg","src":"...","text":"..."}} → 事件流（无请求、随时可能来）。</li>
 * </ul>
 * 这个二分是 REQ-019「输入输出分离」的地基：<b>守护进程没有"标准输出"这个概念</b>，
 * 怎么显示完全由客户端决定，将来的 GUI/网页只是另一个订阅者。
 *
 * <p><b>生命周期</b>：{@code task up} 拉起 → 拿全局锁 {@code ~/.taskmgr/daemon.lock} →
 * 绑 {@code ~/.taskmgr/daemon.sock} → 写 {@code daemon.json} → 接受连接。
 * {@code task down} 走 {@code __shutdown} 优雅停止；{@code task exit} 只是客户端掉线。
 *
 * <p><b>第 2 期：QQ 机器人也归它管</b>。{@code task qqbot up|down|status} 发的是普通命令行，
 * 由本类在 {@link Conn} 里截住、交给 {@link QqBotHost}（与 {@code Main} 的 {@code qqbot} 命令同一份实现）。
 * 托管在守护进程里的好处：关掉任何窗口 QQ 都不断，且提醒推送与提醒扫描同源（只剩一条扫描器路径）。
 *
 * <p><b>不做什么</b>：不做 Windows 服务（要管理员权限 + 第三方 wrapper）；不做远程监听（第 3 期）。
 */
public class Daemon {

    private static final long REMIND_INTERVAL_MS = 10_000L;
    private static final long ICS_INTERVAL_MS = 6L * 60 * 60 * 1000;
    private static final Object LOG_LOCK = new Object();
    /** System.out/err 是否已接管到 daemon.log（见 {@link #installTee()}）。 */
    private static volatile boolean teeInstalled = false;

    /**
     * 标准输出**已经**指向 daemon.log（{@code TaskClient} 拉起时用 {@code Redirect.appendTo} 重定向）。
     *
     * <p><b>为什么需要这个开关</b>：重定向之后如果再 installTee，tee 的两个出口会同时是
     * daemon.log —— 每行日志写两遍（2026-11-06 在服务器上实测到整份日志重复）。
     * 所以这种情况不装 tee，{@code log()} 只 println，交给重定向落到文件。
     */
    private static volatile boolean stdoutIsLog = false;

    private final Gson gson = new Gson();
    private final List<Conn> conns = new CopyOnWriteArrayList<>();
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    /** 关闭流程真正走完的信号（见 {@code run()} 末尾的等待）。 */
    private final java.util.concurrent.CountDownLatch shutdownDone =
            new java.util.concurrent.CountDownLatch(1);
    private final long startedAt = System.currentTimeMillis();

    private volatile boolean running = true;
    private int connSeq;
    private Connection conn;
    private TaskEngine engine;
    private ReminderService reminder;
    private ServerSocketChannel server;
    private SingleInstanceLock lock;
    private ScheduledExecutorService icsTimer;
    /** 第 2 期：QQ 机器人托管（懒开启，{@code task qqbot up} 才连网）。 */
    private QqBotHost qqbotHost;

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
                log("未捕获异常 thread=" + t.getName() + "：" + describe(e)));
        System.exit(new Daemon().run());
    }

    /**
     * 守护进程自己的日志。**必须写文件**：{@code javaw} 没有控制台，
     * {@code System.out} 写进去就消失，出问题将完全无现场（2026-11-01 踩过）。
     */
    private static void log(String msg) {
        String line = java.time.LocalDateTime.now().withNano(0) + " [daemon] " + msg;
        if (teeInstalled || stdoutIsLog) {
            // System.out 已经通向 daemon.log（tee 或启动方的重定向）：只走这一条通道，
            // 避免"两个写者交错"，也避免同一行被写两遍。
            System.out.println(line);
            return;
        }
        synchronized (LOG_LOCK) {
            try {
                java.nio.file.Files.createDirectories(DaemonEndpoint.logDir());
                java.nio.file.Files.writeString(DaemonEndpoint.daemonLogPath(),
                        line + System.lineSeparator(),
                        java.nio.charset.StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 既写日志又写标准输出（前台直接跑时能在控制台看到）。 */
    private static void say(String msg) {
        if (teeInstalled || stdoutIsLog) {
            log(msg);       // log() 内部已改成"只 println"，文件由 tee / 重定向负责
            return;
        }
        log(msg);
        System.out.println(msg);
    }

    /**
     * 把 {@code System.out}/{@code System.err} 接到 {@code daemon.log}，同时保留原控制台输出。
     *
     * <p><b>为什么必须有</b>：{@code javaw} 没有控制台，{@code QqBot} 和 {@code QqWsClient}
     * 里大量的 {@code System.out.println} 会直接消失。第 2 期把 QQ 托管进来之后，
     * 没有这个 tee，"QQ 到底连没连上"将完全没有现场——这正是 2026-11-01 踩过的坑
     * （后台进程静默退出，日志一个字都没有）。
     */
    private static synchronized void installTee() {
        if (teeInstalled) return;
        if (stdoutIsLog) return;    // 启动方已经把 stdout 重定向到 daemon.log，再 tee 就重复了
        try {
            java.nio.file.Files.createDirectories(DaemonEndpoint.logDir());
            java.io.PrintStream real = System.out;
            java.io.OutputStream file = new java.io.FileOutputStream(
                    DaemonEndpoint.daemonLogPath().toFile(), true);
            java.io.PrintStream tee = new java.io.PrintStream(
                    new TeeOutputStream(file, real), true, java.nio.charset.StandardCharsets.UTF_8);
            System.setOut(tee);
            System.setErr(tee);
            teeInstalled = true;
        } catch (Throwable ignored) {
            // 接管失败就退回"只写控制台"：log() 仍会自己写文件，功能不受影响。
        }
    }

    /** 一份字节写两个去处；任一侧出错都不影响另一侧。 */
    private static final class TeeOutputStream extends java.io.OutputStream {
        private final java.io.OutputStream a;
        private final java.io.OutputStream b;

        TeeOutputStream(java.io.OutputStream a, java.io.OutputStream b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public void write(int c) {
            try { a.write(c); } catch (Throwable ignored) { }
            try { b.write(c); } catch (Throwable ignored) { }
        }

        @Override
        public void write(byte[] buf, int off, int len) {
            try { a.write(buf, off, len); } catch (Throwable ignored) { }
            try { b.write(buf, off, len); } catch (Throwable ignored) { }
        }

        @Override
        public void flush() {
            try { a.flush(); } catch (Throwable ignored) { }
            try { b.flush(); } catch (Throwable ignored) { }
        }
    }

    /** @return 进程退出码：0 正常停止｜3 已有实例｜4 环境问题 */
    int run() throws Exception {
        java.nio.file.Files.createDirectories(DaemonEndpoint.home());
        java.nio.file.Files.createDirectories(DaemonEndpoint.logDir());
        // 启动方（TaskClient）用 appendTo(daemon.log) 重定向了 stdout 时会带这个属性：
        // 此时不能再装 tee，否则每行写两遍。手工前台运行（java Daemon）没有它，仍装 tee。
        // ⚠️ 不能用 Boolean.getBoolean()：它只认 "true"，不认 "1"（踩过一次，日志照样重复）。
        String stdoutFlag = System.getProperty("taskmgr.daemon.stdoutIsLog", "");
        stdoutIsLog = stdoutFlag.equals("1") || Boolean.parseBoolean(stdoutFlag);
        // 先接管 stdout/err：后面任何一行（包括 QQ 库里的 println）都要能落盘。
        installTee();

        // 全局锁：两个 task up 抢起来只能有一个赢（比"看 PID 文件"可靠）。
        try {
            lock = SingleInstanceLock.acquire("daemon", "Daemon.main");
        } catch (IOException e) {
            say("[守护进程] 无法创建锁文件: " + e.getMessage());
            return 4;
        }
        if (lock == null) {
            String sep = System.lineSeparator();
            say("[守护进程] 已有另一个实例在运行，本次未启动。");
            say("        当前锁持有者：");
            say("        " + SingleInstanceLock.describeHolder("daemon")
                    .replace(sep, sep + "        "));
            return 3;
        }

        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection("jdbc:sqlite:" + DaemonEndpoint.dbPath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");
            st.execute("PRAGMA busy_timeout=3000;");
        }

        // 引擎的 out 只用于**事件**（提醒、计时播报）；命令回执走 executeFor 自己的缓冲区。
        engine = new TaskEngine(conn);
        engine.setOutput(this::event);

        reminder = new ReminderService(conn);
        reminder.setLogger(this::event);
        reminder.setOnTick(() -> engine.timingTick(this::event));
        // 本地弹窗通道：无头环境（云服务器）或 settings.properties 里 local.popup=0 时为空实现
        reminder.setAlerter(LocalAlert.alerter());
        event("[daemon] 本地弹窗：" + (LocalAlert.enabled()
                ? "开启" : "关闭（无桌面环境，或 " + AppSettings.FILE_NAME + " 中的 "
                        + LocalAlert.KEY_POPUP + "=0）"));
        reminder.start(0, REMIND_INTERVAL_MS);

        // 第 2 期：QQ 机器人懒托管。这里只建壳，真正连网要等 task qqbot up。
        qqbotHost = new QqBotHost(String.valueOf(DaemonEndpoint.dbPath()), this::event);

        icsTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ics-exporter");
            t.setDaemon(true);
            return t;
        });
        icsTimer.scheduleWithFixedDelay(() -> {
            try {
                engine.exportIcs();
            } catch (Throwable ignored) {
            }
        }, 0, ICS_INTERVAL_MS, TimeUnit.MILLISECONDS);

        bind();
        DaemonEndpoint.writeInfo(new DaemonEndpoint.Info(
                ProcessHandle.current().pid(),
                DaemonEndpoint.socketPath().toString(),
                startedAt,
                DaemonEndpoint.PROTOCOL));

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "daemon-shutdown"));
        say("[守护进程] 已启动 pid=" + ProcessHandle.current().pid()
                + " socket=" + DaemonEndpoint.socketPath());

        acceptLoop();
        shutdown();
        // 停止请求也可能来自另一条线程（__shutdown）：等它真的收尾完再返回。
        // 否则主线程紧接着的 System.exit 会把正在关闭的线程掐断——数据库不落盘、
        // socket/锁文件残留（2026-11-06 实测：tasks.db-wal 218KB + daemon.sock 都还在）。
        try {
            shutdownDone.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    private void bind() throws IOException {
        var sock = DaemonEndpoint.socketPath();
        if (java.nio.file.Files.exists(sock)) {
            // 残留 socket 的判定：真连一下。连得上说明真的还有进程在听（理论上锁已经拦住）。
            if (DaemonEndpoint.isRunning()) {
                throw new IOException("socket 已被另一个进程占用: " + sock);
            }
            java.nio.file.Files.deleteIfExists(sock);
            say("[守护进程] 已清理残留 socket 文件");
        }
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(sock));
        server.configureBlocking(true);
    }

    private void acceptLoop() {
        say("[守护进程] 等待客户端连接…");
        while (running) {
            try {
                SocketChannel ch = server.accept();
                Conn c = new Conn(ch, "client-" + (++connSeq));
                conns.add(c);
                c.start();
            } catch (ClosedChannelException e) {
                break;                        // 正常停止（AsynchronousCloseException 是它的子类）
            } catch (Exception e) {
                // ⚠️ 单条连接失败（客户端在 accept 前就断开等）**不能**结束守护进程：
                // 2026-11-01 踩过 —— 一个异常就让 accept 循环 break，整个后台进程静默退出。
                log("[守护进程] accept 失败（继续监听）: " + describe(e));
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log("[守护进程] accept 循环结束");
    }

    /**
     * QQ 机器人子命令（{@code task qqbot up|down|status}）：由守护进程托管，不经过 TaskEngine。
     * 执行完顺手把"提醒是否推到 QQ"重新挂一遍——开关变了，回调就得跟着变（与 Main 语义一致）。
     */
    private String qqbotCommand(String cmd) {
        String reply = qqbotHost.execute(cmd);
        boolean up = qqbotHost.isUp();
        // 三元里放方法引用推断不出目标类型（void 无法转 Boolean），必须写成两句。
        if (up) {
            reminder.setOnTaskRemind(qqbotHost::pushRemind);
        } else {
            reminder.setOnTaskRemind(null);
        }
        log("[QQBot] 命令 " + abbreviate(cmd) + " → " + (up ? "已开启" : "已关闭"));
        return reply;
    }

    /** 广播一条事件给所有已连接客户端（没有人连着时只是丢弃——文件日志里仍有记录）。 */
    private void event(String text) {
        if (text == null || text.isEmpty()) return;
        JsonObject o = new JsonObject();
        o.addProperty("event", "msg");
        o.addProperty("src", "daemon");
        o.addProperty("text", text);
        broadcast(o.toString());
    }

    private void broadcast(String json) {
        for (Conn c : conns) c.send(json);
    }

    private void shutdown() {
        if (!stopped.compareAndSet(false, true)) return;
        running = false;
        // 每一步单独兜异常：以前 `for (Conn c : conns) c.close()` 一旦抛，
        // 后面的 conn.close()/cleanupFiles()/lock.release() 全被跳过 —— 数据库没落盘、
        // socket 和锁文件留在那儿（2026-11-06 在服务器上实测到）。
        try {
            if (server != null) server.close();
        } catch (Throwable ignored) {
        }
        for (Conn c : conns) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
        try {
            if (reminder != null) reminder.stop();
        } catch (Throwable ignored) {
        }
        if (icsTimer != null) icsTimer.shutdownNow();
        try {
            if (qqbotHost != null) qqbotHost.close();
        } catch (Throwable ignored) {
        }
        try {
            if (engine != null) engine.shutdownTiming(m -> { });
        } catch (Throwable ignored) {
        }
        try {
            if (conn != null) conn.close();   // 最后一条连接关闭 → WAL 落进 tasks.db
        } catch (Throwable ignored) {
        }
        try {
            DaemonEndpoint.cleanupFiles();
        } catch (Throwable ignored) {
        }
        try {
            if (lock != null) lock.release();
        } catch (Throwable ignored) {
        }
        say("[守护进程] 已停止 " + java.time.LocalDateTime.now().withNano(0).toLocalTime());
        shutdownDone.countDown();
    }

    /** 一条客户端连接：一行一个 JSON 请求，一行一个 JSON 回复。 */
    private final class Conn {
        private final JsonChannel pipe;
        private final String name;

        Conn(SocketChannel ch, String name) throws IOException {
            this.name = name;
            this.pipe = JsonChannel.open(ch, this::onRequest, () -> {
                conns.remove(this);
                log(name + " 已断开（剩余连接 " + conns.size() + "）");
            });
        }

        void start() {
            log(name + " 已连接");
            pipe.start(name);
        }

        /** 收到一行请求。需要断开的场合自己关连接。 */
        private void onRequest(String line) {
            if (line.isBlank()) return;
            JsonObject req;
            try {
                req = JsonParser.parseString(line).getAsJsonObject();
            } catch (Exception e) {
                pipe.send(errorJson(0, "请求不是合法 JSON"));
                return;
            }
            long id = req.has("id") ? req.get("id").getAsLong() : 0L;
            String cmd = req.has("cmd") ? req.get("cmd").getAsString() : "";

            switch (cmd) {
                case "__ping" -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("id", id);
                    o.addProperty("out", "pong");
                    o.addProperty("pid", ProcessHandle.current().pid());
                    o.addProperty("startedAt", startedAt);
                    o.addProperty("protocol", DaemonEndpoint.PROTOCOL);
                    pipe.send(o.toString());
                }
                case "__shutdown" -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("id", id);
                    o.addProperty("out", "bye");
                    pipe.send(o.toString());
                    // 先回执再停：客户端要能拿到"好的我关了"，而不是连接被硬切断。
                    new Thread(() -> {
                        try {
                            Thread.sleep(100);      // 给回执一点时间出队
                        } catch (InterruptedException ignored) {
                        }
                        Daemon.this.shutdown();
                    }, "shutdown-request").start();
                }
                default -> {
                    String reply;
                    long t0 = System.currentTimeMillis();
                    String lower = cmd.toLowerCase();
                    boolean isQqbot = lower.equals("qqbot") || lower.startsWith("qqbot ");
                    try {
                        reply = isQqbot ? qqbotCommand(cmd) : engine.executeFor(cmd, "cli");
                    } catch (Throwable t) {
                        reply = "[错误] 命令执行失败：" + describe(t);
                    }
                    log("req id=" + id + " cmd=" + abbreviate(cmd)
                            + " | " + (System.currentTimeMillis() - t0) + "ms"
                            + " | out=" + (reply == null ? 0 : reply.length()) + "ch");
                    JsonObject o = new JsonObject();
                    o.addProperty("id", id);
                    o.addProperty("out", reply == null ? "" : reply);
                    o.addProperty("exit", 0);
                    pipe.send(o.toString());
                }
            }
        }

        private String errorJson(long id, String message) {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("out", "[错误] " + message);
            o.addProperty("exit", 1);
            return o.toString();
        }

        void send(String json) {
            pipe.send(json);
        }

        void close() {
            pipe.close();
        }
    }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }
}
