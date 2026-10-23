import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * WsSessionStore —— WebSocket 会话持久化（用户需求，对应 §12.12「session 持久化」）。
 * <p>
 * <b>为什么需要</b>：QQ 的补发机制靠 <b>Opcode 6 Resume</b>，而 Resume 需要三样东西：
 * {@code session_id}、{@code seq}（最后收到的序列号）、以及当初的 {@code gateway} 地址。
 * 原先这三样<b>只存在内存</b>里，一旦进程退出（或像 BUG-005 那样连接卡死）就全丢，
 * 下次只能走全新 {@code Identify} —— <b>QQ 就无法补发漏掉的事件</b>，消息永久丢失。
 * <p>
 * <b>做法</b>：每次连接成功（READY/RESUMED）以及每收到一个事件（seq 推进）就落盘；
 * 下次启动时先读回来，<b>优先尝试 Resume</b>，失败才退回全新登录。
 * <p>
 * <b>⚠️ 重要边界（不要误解这个功能的能力）</b>：
 * <ul>
 *   <li>持久化 <b>不能让过期的 session 复活</b>。若 QQ 侧已回收会话，Resume 仍会失败
 *       （返回 4006/4007/4009 或 Invalid Session），此时必须清掉本地记录、退回 Identify。</li>
 *   <li>它的价值在于<b>覆盖"进程重启"场景</b>——此前这类场景 100% 丢失补发能力。</li>
 *   <li>文件里<b>不含任何密钥</b>，只有会话标识，但仍放在用户目录下、不进版本库。</li>
 * </ul>
 * 存储位置：{@code ~/.taskmgr/ws_session.properties}
 */
public class WsSessionStore {

    private static final String FILE_NAME = "ws_session.properties";
    private static final String K_SESSION = "session_id";
    private static final String K_SEQ = "seq";
    private static final String K_GATEWAY = "gateway";
    private static final String K_UPDATED = "updated_at";

    private final File file;

    public WsSessionStore() {
        this(new File(System.getProperty("user.home") + File.separator
                + ".taskmgr" + File.separator + FILE_NAME));
    }

    /** 允许指定路径（便于测试）。 */
    public WsSessionStore(File file) {
        this.file = file;
    }

    public File file() {
        return file;
    }

    /** 一条已保存的会话记录。 */
    public static class Session {
        public final String sessionId;
        public final long seq;
        public final String gatewayUrl;

        public Session(String sessionId, long seq, String gatewayUrl) {
            this.sessionId = sessionId;
            this.seq = seq;
            this.gatewayUrl = gatewayUrl;
        }
    }

    /**
     * 保存（覆盖）当前会话。任一步失败都<b>静默忽略</b>——持久化只是增强，
     * 绝不能因为它出错而影响收发消息。
     *
     * @param sessionId  会话 id（为空则不保存）
     * @param seq        最后收到的序列号；没有则传 -1（表示尚未收到任何事件）
     * @param gatewayUrl 当初使用的网关地址（Resume 必须用同一网关）
     */
    public synchronized void save(String sessionId, long seq, String gatewayUrl) {
        if (sessionId == null || sessionId.isEmpty()) return;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return;
        Properties p = new Properties();
        p.setProperty(K_SESSION, sessionId);
        p.setProperty(K_SEQ, Long.toString(seq));
        if (gatewayUrl != null) p.setProperty(K_GATEWAY, gatewayUrl);
        p.setProperty(K_UPDATED, java.time.LocalDateTime.now().toString());
        try (FileOutputStream out = new FileOutputStream(file)) {
            p.store(out, "TaskMgr QQ WebSocket session - auto generated, do not edit");
        } catch (IOException ignore) {
            // 落盘失败不影响主流程
        }
    }

    /**
     * 读取已保存的会话。
     *
     * @return 记录；文件不存在 / 内容不完整时返回 {@code null}
     */
    public synchronized Session load() {
        if (!file.isFile()) return null;
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(file)) {
            p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
        String sid = p.getProperty(K_SESSION, "").trim();
        if (sid.isEmpty()) return null;
        long seq;
        try {
            seq = Long.parseLong(p.getProperty(K_SEQ, "-1").trim());
        } catch (NumberFormatException e) {
            seq = -1;
        }
        String gw = p.getProperty(K_GATEWAY, "").trim();
        return new Session(sid, seq, gw.isEmpty() ? null : gw);
    }

    /** 清掉本地会话（session 已失效时必须调用，避免每次都用坏 session 重试）。 */
    public synchronized void clear() {
        try {
            if (file.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        } catch (Throwable ignore) {
        }
    }
}
