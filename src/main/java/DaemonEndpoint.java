import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * DaemonEndpoint —— 守护进程与客户端共用的「沙盘路径 + 状态文件 + 连通性探测」（REQ-019）。
 *
 * <p><b>为什么用 Unix domain socket 而不是 TCP 端口</b>（2026-11-01 本机实测 JDK 17.0.2 + Win10 可用）：
 * <ul>
 *   <li>不用挑端口、不会撞端口；</li>
 *   <li>socket 文件放在 {@code ~/.taskmgr/} 下，天然受目录 ACL 保护，局域网里的其他机器连不上
 *       （对比 BUG-017：webhook 绑 {@code 0.0.0.0} 是把门开在墙上）；</li>
 *   <li>不用命名管道：纯 Java 无法创建 {@code \\.\pipe\*}（要 JNI/JNA）。</li>
 * </ul>
 *
 * <p><b>安全边界 = 文件权限</b>：Java 拿不到 UDS 对端 PID/UID，个人自用足够；
 * 将来若要开给手机，再在协议上叠一层 token。
 *
 * <p><b>状态文件用 properties 风格而不是 JSON</b>：只有 4 个字段，人肉可读，
 * 也让客户端在最坏情况下（守护进程没起来）不必依赖任何 JSON 解析。
 */
public final class DaemonEndpoint {

    /** 协议版本。改协议必须 +1，客户端据此判断"是否该提示版本不一致"。 */
    public static final int PROTOCOL = 1;

    private DaemonEndpoint() {
    }

    /** 数据目录：{@code -Dtaskmgr.home=<dir>} 可覆盖（测试用临时目录，绝不碰真实库）。 */
    public static Path home() {
        String override = System.getProperty("taskmgr.home");
        if (override != null && !override.isBlank()) return Path.of(override);
        return Path.of(System.getProperty("user.home"), ".taskmgr");
    }

    public static Path dbPath() {
        return home().resolve("tasks.db");
    }

    public static Path socketPath() {
        return home().resolve("daemon.sock");
    }

    public static Path infoPath() {
        return home().resolve("daemon.json");
    }

    public static Path logDir() {
        return home().resolve("logs");
    }

    public static Path daemonLogPath() {
        return logDir().resolve("daemon.log");
    }

    /** 守护进程状态（握手/排障用）。{@code startedAt} 为 epoch 毫秒。 */
    public record Info(long pid, String socket, long startedAt, int protocol) {
    }

    /** 写入状态文件（失败不影响运行，仅影响诊断）。 */
    public static void writeInfo(Info info) {
        String text = "pid=" + info.pid()
                + "\nsocket=" + info.socket()
                + "\nstartedAt=" + info.startedAt()
                + "\nprotocol=" + info.protocol()
                + "\n";
        try {
            Files.createDirectories(home());
            Files.writeString(infoPath(), text, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    /** 读取状态文件；不存在或残缺返回 {@code null}。 */
    public static Info readInfo() {
        try {
            Path p = infoPath();
            if (!Files.exists(p)) return null;
            String text = Files.readString(p, StandardCharsets.UTF_8);
            Long pid = null, startedAt = null;
            Integer protocol = null;
            String socket = null;
            for (String raw : text.split("\n")) {
                String line = raw.trim();
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq);
                String v = line.substring(eq + 1);
                switch (k) {
                    case "pid" -> pid = Long.parseLong(v);
                    case "startedAt" -> startedAt = Long.parseLong(v);
                    case "protocol" -> protocol = Integer.parseInt(v);
                    case "socket" -> socket = v;
                    default -> { }
                }
            }
            if (pid == null || socket == null) return null;
            return new Info(pid, socket, startedAt == null ? 0L : startedAt,
                    protocol == null ? 0 : protocol);
        } catch (Exception e) {
            return null;
        }
    }

    /** 删除状态文件与 socket 文件（停止时调用；残留 socket 会让"存活判定"骗人）。 */
    public static void cleanupFiles() {
        try {
            Files.deleteIfExists(infoPath());
        } catch (IOException ignored) {
        }
        try {
            Files.deleteIfExists(socketPath());
        } catch (IOException ignored) {
        }
    }

    /** 打开一条到守护进程的连接。调用方负责关闭。 */
    public static SocketChannel connect() throws IOException {
        return SocketChannel.open(UnixDomainSocketAddress.of(socketPath()));
    }

    /**
     * 存活判定：**真连一下**，不信 PID 文件也不信 socket 文件是否存在。
     * <p>崩溃后残留的 socket 文件会让"看起来在跑"骗人，这是唯一可靠的判据。
     */
    public static boolean isRunning() {
        if (!Files.exists(socketPath())) return false;
        try (SocketChannel ch = connect()) {
            // 注意：UDS 的 SocketChannel.socket() 不支持 setSoTimeout 之类的选项，不要设。
            // 守护进程会立即回 "__ping"，因此不需要读超时；对端假死时由调用方的整体超时兜底。
            InputStream in = new BufferedInputStream(Channels.newInputStream(ch));
            OutputStream out = new BufferedOutputStream(Channels.newOutputStream(ch));
            out.write("{\"id\":0,\"cmd\":\"__ping\"}\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            String reply = readLine(in);
            return reply != null && reply.contains("\"pong\"");
        } catch (Exception e) {
            return false;
        }
    }

    /** 按 UTF-8 读一行（不依赖平台默认字符集）。返回 {@code null} 表示流已结束。 */
    public static String readLine(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(256);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') buf.write(b);
        }
        if (b == -1 && buf.size() == 0) return null;
        return buf.toString(StandardCharsets.UTF_8);
    }

    /** 按 UTF-8 写一行（含换行符）。 */
    public static void writeLine(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
