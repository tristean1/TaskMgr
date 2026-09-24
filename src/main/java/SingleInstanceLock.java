import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;

/**
 * SingleInstanceLock —— 跨进程单实例保护（BUG-001）。
 * <p>
 * <b>为什么需要</b>：曾经出现过两个 QqBot 同时运行的情况，后果有三：
 * <ol>
 *   <li>同一条消息被处理两次、回复两条（命令恰好幂等才没出事，但 {@code add} 不幂等）；</li>
 *   <li>两个进程各持一个 WebSocket 连接，<b>QQ 侧互相踢连接</b> → 疯狂重连
 *       （实测日志：每 30~55 秒断一次，{@code code=1006}，17 分钟重连十余次）；</li>
 *   <li>重连走的是<b>全新 Identify</b> 而非 Resume，<b>断连期间的 QQ 消息可能丢失</b>。</li>
 * </ol>
 * <p>
 * <b>为什么用文件锁而不是内存标志</b>：双实例是<b>两个独立的 JVM 进程</b>，
 * 进程内的 {@code static} 标志彼此不可见，只有 OS 级的东西（文件锁 / 端口 / 互斥量）才能跨进程。
 * <p>
 * <b>为什么不用端口占用检测</b>：QqBot 在 websocket 模式下<b>不监听任何端口</b>
 * （webhook 模式才监听 9000），端口检测在默认模式下无效。
 * <p>
 * 用法（必须在真正干活之前调用）：
 * <pre>
 *   SingleInstanceLock lock = SingleInstanceLock.acquire("qqbot", "Main(qqbot up)");
 *   if (lock == null) { System.out.println("已有实例在运行"); return; }
 *   try { ... 正常逻辑 ... } finally { lock.release(); }
 * </pre>
 * <b>注意</b>：{@link FileLock} 由 JVM 持有，进程被强杀（kill -9）时 OS 会自动释放，
 * 因此不会留下"僵尸锁"。锁文件本身残留是正常的，不影响下次加锁。
 * <p>
 * <b>诊断信息（BUG-001 加固）</b>：加锁成功时会把「pid / 时间 / 启动入口」写进
 * {@code <name>.lock.owner} 旁路文件。之所以不写在被锁的 {@code .lock} 文件里，
 * 是因为 Windows 上 {@link FileLock} 是<b>强制锁</b>，别的进程根本读不到被锁区间。
 * {@link #describeHolder(String)} 可跨进程读取该信息，并判断持有者 PID 是否仍然存活，
 * 便于 {@code qqbot status} 和启动失败提示一眼看出"是谁占着锁"。
 */
public class SingleInstanceLock {

    /** 锁文件所在目录（与数据库同目录，用户级、不污染工程）。 */
    private static final String LOCK_DIR =
            System.getProperty("user.home") + File.separator + ".taskmgr";

    private final String name;
    private final RandomAccessFile raf;
    private final FileChannel channel;
    private final FileLock lock;
    private final File file;

    private SingleInstanceLock(String name, File file,
                               RandomAccessFile raf, FileChannel channel, FileLock lock) {
        this.name = name;
        this.file = file;
        this.raf = raf;
        this.channel = channel;
        this.lock = lock;
    }

    /** 尝试获取名为 {@code name} 的单实例锁（不记录启动入口）。 */
    public static SingleInstanceLock acquire(String name) throws IOException {
        return acquire(name, null);
    }

    /**
     * 尝试获取名为 {@code name} 的单实例锁。
     *
     * @param name       锁名（如 "qqbot"），会生成 {@code ~/.taskmgr/<name>.lock}
     * @param holderRole 启动入口描述（如 "QqBot.main" / "Main(qqbot up)"），仅用于诊断
     * @return 成功返回锁对象，<b>已被其他进程占用时返回 {@code null}</b>（调用方据此提示并退出）
     * @throws IOException 仅当锁文件本身无法创建/写入（如磁盘权限问题），此时不应静默
     */
    public static SingleInstanceLock acquire(String name, String holderRole) throws IOException {
        File dir = new File(LOCK_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建锁目录: " + LOCK_DIR);
        }
        File f = new File(dir, name + ".lock");
        RandomAccessFile raf = new RandomAccessFile(f, "rw");
        FileChannel ch = raf.getChannel();
        FileLock l;
        try {
            // tryLock 立即返回：拿不到就是 null（不阻塞、不等待）
            l = ch.tryLock();
        } catch (OverlappingFileLockException e) {
            // 同一 JVM 内重复加锁（例如 Main 里 qqbot up 连点两次）
            l = null;
        } catch (IOException e) {
            closeQuietly(ch, raf);
            throw e;
        }
        if (l == null) {
            closeQuietly(ch, raf);
            return null;
        }
        // 记录持有者信息，便于排查"是谁占着锁"
        String info = "pid=" + ProcessHandle.current().pid()
                + " since=" + java.time.LocalDateTime.now()
                + (holderRole == null || holderRole.isEmpty() ? "" : " mainClass=" + holderRole)
                + System.lineSeparator();
        try {
            raf.setLength(0);
            raf.write(info.getBytes(StandardCharsets.UTF_8));
            raf.getChannel().force(true);
        } catch (Throwable ignore) {
            // 写诊断信息失败不影响加锁本身
        }
        // 旁路文件：Windows 上被 FileLock 锁住的文件别的进程读不了，故额外写一份供跨进程查看
        try {
            java.nio.file.Files.write(ownerFile(name).toPath(), info.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignore) {
        }
        return new SingleInstanceLock(name, f, raf, ch, l);
    }

    /** 旁路持有者信息文件路径。 */
    private static File ownerFile(String name) {
        return new File(LOCK_DIR, name + ".lock.owner");
    }

    /** 读取锁文件里记录的持有者信息（可能为空）。便于诊断。 */
    public String ownerInfo() {
        try {
            byte[] buf = new byte[(int) Math.min(512, raf.length())];
            raf.seek(0);
            int n = raf.read(buf);
            return n <= 0 ? "" : new String(buf, 0, n, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 跨进程查看锁持有者信息，并判断其 PID 是否仍存活。
     * 供 {@code qqbot status} 与"启动被拦截"的提示使用。
     */
    public static String describeHolder(String name) {
        File of = ownerFile(name);
        File lf = new File(LOCK_DIR, name + ".lock");
        String info = readText(of);
        Boolean held = probeHeld(lf); // null=无法判断，TRUE=正被持有，FALSE=空闲

        if (info.isEmpty()) {
            if (Boolean.FALSE.equals(held)) {
                return "无（未在运行；锁文件为上次运行残留，可直接启动）";
            }
            if (Boolean.TRUE.equals(held)) {
                return "（有进程持有锁，但未记录持有者信息：可能是旧版本启动的实例）";
            }
            return "（无法判断：锁文件 " + lf.getAbsolutePath() + " 不可读写）";
        }

        StringBuilder sb = new StringBuilder(info);
        Long pid = parsePid(info);
        if (pid != null) {
            boolean alive;
            try {
                alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            } catch (Throwable t) {
                alive = true; // 查询失败时不妄下结论
            }
            sb.append(System.lineSeparator())
              .append("  进程存活: ")
              .append(alive
                      ? "是（如非本程序，请确认是否为残留的调试会话）"
                      : "否（疑似残留锁，可删除锁文件后重试）");
        }
        if (Boolean.TRUE.equals(held)) {
            sb.append(System.lineSeparator()).append("  锁状态: 当前被持有");
        } else if (Boolean.FALSE.equals(held)) {
            sb.append(System.lineSeparator())
              .append("  锁状态: 当前空闲（持有者已退出；以上为残留信息，可忽略）");
        }
        return sb.toString();
    }

    /** 探测锁文件当前是否真的被持有：自己 tryLock 一下再立即释放。null=无法判断。 */
    private static Boolean probeHeld(File lockFile) {
        if (!lockFile.exists()) return Boolean.FALSE;
        try (RandomAccessFile r = new RandomAccessFile(lockFile, "rw");
             FileChannel c = r.getChannel()) {
            FileLock l = null;
            try {
                l = c.tryLock();
                return l == null ? Boolean.TRUE : Boolean.FALSE;
            } catch (OverlappingFileLockException e) {
                return Boolean.TRUE; // 本进程已持有
            } finally {
                if (l != null) {
                    try { l.release(); } catch (Exception ignore) {}
                }
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static String readText(File f) {
        try {
            if (!f.exists()) return "";
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            return new String(b, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static Long parsePid(String info) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("pid=(\\d+)").matcher(info);
        return m.find() ? Long.parseLong(m.group(1)) : null;
    }

    /** 锁文件路径。 */
    public File file() {
        return file;
    }

    /** 释放锁（幂等，可重复调用）。应在 {@code finally} 中调用。 */
    public void release() {
        // 先清旁路信息，再释放 OS 锁：本项目加锁不做阻塞等待，不存在"新持有者刚写入就被旧持有者删掉"的竞态。
        try {
            java.nio.file.Files.deleteIfExists(ownerFile(name).toPath());
        } catch (Throwable ignore) {
        }
        try {
            if (lock != null && lock.isValid()) lock.release();
        } catch (Exception ignore) {
        }
        closeQuietly(channel, raf);
    }

    private static void closeQuietly(FileChannel ch, RandomAccessFile raf) {
        try {
            if (ch != null) ch.close();
        } catch (Exception ignore) {
        }
        try {
            if (raf != null) raf.close();
        } catch (Exception ignore) {
        }
    }
}
