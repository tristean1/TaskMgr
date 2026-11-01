import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TaskClient —— `task` 命令的客户端（REQ-019 第 1 期）。
 *
 * <pre>
 *   task                进入交互（attach 到守护进程），之后每行都是一条命令，exit 退出
 *   task &lt;命令&gt;         一次性发送一条命令（如 task list）
 *   task up             启动守护进程（幂等）
 *   task down           优雅停止守护进程
 *   task status         守护进程状态
 * </pre>
 *
 * <p><b>为什么命令文本从句环境变量 {@code TASKCMDLINE} 读，而不是 argv</b>：
 * 本机 JDK 17.0.2 的启动器按 ACP(65001) 把命令行转成 UTF-8，JVM 内部却按
 * {@code sun.jnu.encoding}(GBK) 解回来 —— 中文 argv 必坏（实测 `写论文` → `鍐欒鏂?`，
 * 与 AGENTS.md 铁律 4 一致）。而 Windows 环境块本身是 UTF-16，Java 用宽字符 API 读取，
 * **中文经环境变量可以完好通过**（2026-11-01 实测：`env=add 写论文 -t 明天`）。
 * 所以 {@code task.cmd} 用 {@code set "TASKCMDLINE=%*"} 传命令，绝不走 argv。
 *
 * <p><b>输入输出分离</b>：交互模式下有两路数据——命令回执（有始有终）与事件（随时可能来）。
 * 有控制台时用 JLine 的 {@code printAbove} 把事件印在提示行上方并重绘输入行；
 * 没有 JLine/没有控制台时退化为一"事件排队、回车后冲刷"，保证**打字时一个字都不写屏**。
 */
public class TaskClient {

    /** {@code task.cmd} 注入的命令文本（不经过 argv，避免中文损坏）。 */
    private static final String ENV_LINE = "TASKCMDLINE";

    /** 设了 {@code TASK_DEBUG=1} 就把客户端侧的连接/收发记到 {@code logs/client.log}。 */
    private static final boolean DEBUG = System.getenv("TASK_DEBUG") != null;

    private static void debug(String msg) {
        if (!DEBUG) return;
        try {
            Files.createDirectories(DaemonEndpoint.logDir());
            Files.writeString(DaemonEndpoint.logDir().resolve("client.log"),
                    java.time.LocalDateTime.now().withNano(0) + " [client] " + msg
                            + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
        }
    }

    public static void main(String[] args) throws Exception {
        String fromEnv = System.getenv(ENV_LINE);
        String cmdLine = (fromEnv != null ? fromEnv : String.join(" ", args)).trim();
        if (cmdLine.isEmpty()) {
            if (!running()) {
                hintNotRunning();
                System.exit(1);
                return;
            }
            attach();
            return;
        }

        String head = cmdLine.split("\\s+", 2)[0].toLowerCase();
        switch (head) {
            case "up" -> up();
            case "down" -> down();
            case "status" -> status();
            case "help", "?", "--help" -> usage();
            case "exit", "quit" ->
                    System.out.println("（当前不在客户端内；exit 只在用 task 进入交互后生效）");
            default -> oneShot(cmdLine);
        }
    }

    // ---------------------------------------------------------------- 子命令

    /** 启动守护进程（幂等）：已在跑就报状态。用 javaw 拉，脱离控制台，关窗口不死。 */
    private static void up() throws Exception {
        if (running()) {
            printStatus(true);
            return;
        }
        spawnDaemon();
        for (int i = 0; i < 200; i++) {
            if (running()) {
                printStatus(true);
                return;
            }
            Thread.sleep(100);
        }
        System.out.println("[错误] 守护进程启动超时（20 秒）。日志：" + DaemonEndpoint.daemonLogPath());
        System.exit(1);
    }

    private static void spawnDaemon() throws IOException {
        String javaHome = System.getProperty("java.home");
        File javaw = new File(javaHome, "bin" + File.separator + "javaw.exe");
        File java = new File(javaHome, "bin" + File.separator + "java.exe");
        // javaw 是 GUI 子系统程序：不附着任何控制台，因此关掉命令行窗口也不会被带走。
        String exe = javaw.isFile() ? javaw.getAbsolutePath() : java.getAbsolutePath();

        Files.createDirectories(DaemonEndpoint.logDir());
        ProcessBuilder pb = new ProcessBuilder(
                exe,
                "-Dfile.encoding=UTF-8",
                "-Dtaskmgr.home=" + DaemonEndpoint.home(),
                "-cp", System.getProperty("java.class.path"),
                "Daemon");
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(DaemonEndpoint.daemonLogPath().toFile()));
        pb.start();
    }

    /** 优雅停止守护进程：发 __shutdown，等 socket 文件消失。 */
    private static void down() throws Exception {
        if (!running()) {
            System.out.println("[提示] 没有正在运行的后台进程");
            return;
        }
        try (Link link = Link.open()) {
            link.startReader();
            link.exec("__shutdown", 3000);
        }
        for (int i = 0; i < 50; i++) {
            if (!DaemonEndpoint.isRunning()) {
                System.out.println("[守护进程] 已停止");
                return;
            }
            Thread.sleep(100);
        }
        System.out.println("[提示] 已发出停止请求，但进程仍在收尾。可用 task status 复查。");
    }

    private static void status() {
        if (!running()) {
            hintNotRunning();
            System.exit(1);
            return;
        }
        printStatus(false);
    }

    private static void printStatus(boolean justStarted) {
        System.out.println(statusText(justStarted));
    }

    /** 状态文本。客户端本地回答，**不发给引擎**（引擎不认识 status，会回"未知命令"）。 */
    private static String statusText(boolean justStarted) {
        StringBuilder sb = new StringBuilder();
        sb.append(justStarted ? "[守护进程] 已就绪" : "[守护进程] 运行中");
        DaemonEndpoint.Info info = DaemonEndpoint.readInfo();
        if (info != null) {
            long upSec = (System.currentTimeMillis() - info.startedAt()) / 1000;
            sb.append("\n  PID      : ").append(info.pid());
            sb.append("\n  已运行   : ").append(upSec / 3600).append(" 小时 ")
                    .append(upSec % 3600 / 60).append(" 分");
            sb.append("\n  Socket   : ").append(info.socket());
        }
        sb.append("\n  日志     : ").append(DaemonEndpoint.daemonLogPath());
        sb.append("\n  用法     : task 进入交互 ｜ task down 停止");
        return sb.toString();
    }

    /**
     * 客户端本地的命令（不转发给引擎）。{@code status/up/down} 属于**进程生命周期**，
     * 不属于任务命令——引擎里根本没有这三个词，转发过去只会得到「未知命令」。
     * 2026-11-01 就是这样踩的：交互里敲 {@code status} 回了空。
     */
    private static String localCommand(String line) {
        switch (line.toLowerCase()) {
            case "status" -> {
                return statusText(false);
            }
            case "up" -> {
                return "[提示] 后台进程已在运行（当前窗口就是它的客户端）";
            }
            case "down" -> {
                return "[提示] 请另开一个命令行执行 task down；当前窗口断开用 exit";
            }
            default -> {
                return null;
            }
        }
    }

    /** 一次性命令：发一条，打印回执。 */
    private static void oneShot(String cmd) throws Exception {
        if (!running()) {
            if (cmd.toLowerCase().startsWith("qqbot")) {
                // QQ 机器人托管在后台进程里（第 2 期），没有后台进程就没人替它连网。
                System.out.println("[提示] QQ 机器人现在由后台进程托管，先执行 task up 再试");
                System.exit(1);
                return;
            }
            hintNotRunning();
            System.exit(1);
            return;
        }
        try (Link link = Link.open()) {
            link.startReader();
            String out = link.exec(cmd, 15_000);
            if (out == null) {
                System.out.println("[错误] 等待守护进程回执超时");
                System.exit(1);
            }
            System.out.println(out);
        }
    }

    // ---------------------------------------------------------------- 交互模式

    private static void attach() throws Exception {
        try (Link link = Link.open()) {
            link.startReader();
            if (hasConsole() && attachWithJLine(link)) return;
            attachPlain(link);
        }
    }

    private static boolean hasConsole() {
        try {
            return System.console() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 有 JLine 时的交互：事件走 {@code printAbove}，印在提示行上方并重绘输入行——
     * 这就是"输入不被截断"的正解（纯 Java 无库做不到，见 REQ-019 §19.5）。
     */
    private static boolean attachWithJLine(Link link) {
        try {
            org.jline.terminal.Terminal terminal =
                    org.jline.terminal.TerminalBuilder.builder().system(true).build();
            org.jline.reader.LineReader reader =
                    org.jline.reader.LineReaderBuilder.builder()
                            .terminal(terminal)
                            .variable(org.jline.reader.LineReader.HISTORY_FILE,
                                    DaemonEndpoint.home().resolve("history"))
                            .build();
            link.setEventSink(text -> {
                try {
                    reader.printAbove(text);
                } catch (Throwable ignored) {
                }
            });
            java.io.PrintWriter w = terminal.writer();
            w.println(banner(link));
            w.flush();
            while (true) {
                String line;
                try {
                    line = reader.readLine("> ");
                } catch (org.jline.reader.UserInterruptException e) {
                    continue;
                } catch (org.jline.reader.EndOfFileException e) {
                    break;
                }
                if (line == null) break;
                line = line.trim();
                if (line.isEmpty()) continue;
                if (isExit(line)) break;
                String local = localCommand(line);
                if (local != null) {
                    w.println(local);
                    w.flush();
                    continue;
                }
                debug("exec: " + line);
                String out = link.exec(line, 120_000);
                debug("reply: " + (out == null ? "<null>" : out.length() + "ch"));
                if (out == null) {
                    w.println("[错误] 等待守护进程回执超时");
                } else if (!out.isEmpty()) {
                    w.println(out);
                }
                w.flush();
            }
            w.println("已退出客户端（守护进程继续在后台运行）");
            w.flush();
            return true;
        } catch (Throwable t) {
            return false;   // 没有 JLine 或终端不可用 → 走退化路径
        }
    }

    /** 无 JLine/无控制台的退化交互：事件在"打字期间"排队，回车后一次性冲刷。 */
    private static void attachPlain(Link link) throws Exception {
        PlainSink sink = new PlainSink();
        link.setEventSink(sink::onEvent);
        InputStream in = new BufferedInputStream(System.in);
        System.out.println(banner(link));
        while (true) {
            sink.beginPrompt();
            System.out.print("> ");
            System.out.flush();
            String line = DaemonEndpoint.readLine(in);
            sink.endPrompt();
            if (line == null) break;
            line = line.trim();
            if (line.isEmpty()) continue;
            if (isExit(line)) break;
            String local = localCommand(line);
            if (local != null) {
                System.out.println(local);
                continue;
            }
            String out = link.exec(line, 120_000);
            if (out == null) {
                System.out.println("[错误] 等待守护进程回执超时");
            } else if (!out.isEmpty()) {
                System.out.println(out);
            }
        }
        System.out.println("已退出客户端（守护进程继续在后台运行）");
    }

    /** 事件汇：打字期间入队（一个字都不写屏），回车后冲刷。 */
    private static final class PlainSink {
        private final ArrayDeque<String> pending = new ArrayDeque<>();
        private boolean atPrompt;

        synchronized void onEvent(String text) {
            if (atPrompt) {
                pending.add(text);
            } else {
                System.out.println(text);
            }
        }

        synchronized void beginPrompt() {
            atPrompt = true;
        }

        synchronized void endPrompt() {
            atPrompt = false;
            while (!pending.isEmpty()) System.out.println(pending.poll());
        }
    }

    private static String banner(Link link) {
        DaemonEndpoint.Info info = DaemonEndpoint.readInfo();
        String pid = info == null ? "?" : String.valueOf(info.pid());
        return "[客户端] 已连接守护进程 pid=" + pid
                + "。直接输入命令（列表、add、done、next…），exit 退出。";
    }

    private static boolean isExit(String line) {
        String s = line.toLowerCase();
        return s.equals("exit") || s.equals("quit");
    }

    private static boolean running() {
        return DaemonEndpoint.isRunning();
    }

    private static void hintNotRunning() {
        System.out.println("[提示] 没有正在运行的后台进程，先执行 task up");
    }

    private static void usage() {
        System.out.println("""
                task —— 效率工具客户端
                  task                 进入交互，之后每行一条命令，exit 退出
                  task <命令>          一次性发送（等同在交互里敲一行）
                  task up              启动后台进程（幂等，已在跑则只报状态）
                  task down            优雅停止后台进程
                  task status          查看后台进程状态
                  task qqbot up        在后台进程里开启 QQ 机器人（关窗口不掉）
                  task qqbot down      关闭 QQ 机器人
                  task qqbot status    查看 QQ 机器人状态
                """);
    }

    // ---------------------------------------------------------------- 连接

    /** 到守护进程的一条连接：请求/回执用 id 配对，事件走 eventSink。 */
    private static final class Link implements AutoCloseable {
        private final BlockingQueue<JsonObject> responses = new LinkedBlockingQueue<>();
        private final AtomicLong seq = new AtomicLong();
        private volatile Consumer2 eventSink;
        private volatile boolean closed;
        private JsonChannel pipe;

        private Link() {
        }

        static Link open() throws IOException {
            Link link = new Link();
            link.pipe = JsonChannel.open(DaemonEndpoint.connect(), link::onLine,
                    () -> link.closed = true);
            return link;
        }

        /** 简单的可空消费接口（避免和 java.util.function 的命名冲突）。 */
        interface Consumer2 {
            void accept(String text);
        }

        void setEventSink(Consumer2 sink) {
            this.eventSink = sink;
        }

        void startReader() {
            pipe.start("daemon-reader");
        }

        private void onLine(String line) {
            JsonObject o;
            try {
                o = JsonParser.parseString(line).getAsJsonObject();
            } catch (Exception e) {
                return;
            }
            if (o.has("event")) {
                Consumer2 sink = eventSink;
                String text = o.has("text") ? o.get("text").getAsString() : "";
                if (sink != null && !text.isEmpty()) sink.accept(text);
            } else {
                responses.offer(o);
            }
        }

        /** 发送命令并等待它的回执。{@code null} = 超时或连接已断。 */
        String exec(String cmd, long timeoutMs) throws InterruptedException {
            long id = seq.incrementAndGet();
            JsonObject req = new JsonObject();
            req.addProperty("id", id);
            req.addProperty("cmd", cmd);
            pipe.send(req.toString());
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) return null;
                JsonObject o = responses.poll(Math.min(wait, 500), TimeUnit.MILLISECONDS);
                if (o == null) {
                    if (closed) return null;
                    continue;
                }
                if (!o.has("id")) return null;          // 断连哨兵
                if (o.get("id").getAsLong() != id) continue;
                return o.has("out") ? o.get("out").getAsString() : "";
            }
        }

        @Override
        public void close() {
            closed = true;
            if (pipe != null) pipe.close();
            responses.offer(new JsonObject());          // 唤醒等待者
        }
    }
}
