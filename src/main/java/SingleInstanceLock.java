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
 *   SingleInstanceLock lock = SingleInstanceLock.acquire("qqbot");
 *   if (lock == null) { System.out.println("已有实例在运行"); return; }
 *   try { ... 正常逻辑 ... } finally { lock.release(); }
 * </pre>
 * <b>注意</b>：{@link FileLock} 由 JVM 持有，进程被强杀（kill -9）时 OS 会自动释放，
 * 因此不会留下"僵尸锁"。锁文件本身残留是正常的，不影响下次加锁。
 */
public class SingleInstanceLock {

    /** 锁文件所在目录（与数据库同目录，用户级、不污染工程）。 */
    private static final String LOCK_DIR =
            System.getProperty("user.home") + File.separator + ".taskmgr";

    private final RandomAccessFile raf;
    private final FileChannel channel;
    private final FileLock lock;
    private final File file;

    private SingleInstanceLock(File file, RandomAccessFile raf, FileChannel channel, FileLock lock) {
        this.file = file;
        this.raf = raf;
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * 尝试获取名为 {@code name} 的单实例锁。
     *
     * @param name 锁名（如 "qqbot"），会生成 {@code ~/.taskmgr/<name>.lock}
     * @return 成功返回锁对象，<b>已被其他进程占用时返回 {@code null}</b>（调用方据此提示并退出）
     * @throws IOException 仅当锁文件本身无法创建/写入（如磁盘权限问题），此时不应静默
     */
    public static SingleInstanceLock acquire(String name) throws IOException {
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
        try {
            String info = "pid=" + ProcessHandle.current().pid()
                    + " since=" + java.time.LocalDateTime.now()
                    + System.lineSeparator();
            raf.setLength(0);
            raf.write(info.getBytes(StandardCharsets.UTF_8));
            raf.getChannel().force(true);
        } catch (Throwable ignore) {
            // 写诊断信息失败不影响加锁本身
        }
        return new SingleInstanceLock(f, raf, ch, l);
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

    /** 锁文件路径。 */
    public File file() {
        return file;
    }

    /** 释放锁（幂等，可重复调用）。应在 {@code finally} 中调用。 */
    public void release() {
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
