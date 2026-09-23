import javax.swing.JOptionPane;
import java.awt.Toolkit;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 独立提醒服务，从 QqBot 提炼提醒最小逻辑并独立，不依赖 QQ。
 * 扫描数据库中的任务，到达提醒时间时弹窗+beep。
 */
public class ReminderService {
    private final Connection conn;            // 外部传入，调用方负责 open/close
    private ScheduledExecutorService timer;
    /** 到点提醒后额外回调（供主命令行接入 QQ 推送等），可为 null。 */
    private volatile java.util.function.Consumer<String> onTaskRemind;

    public ReminderService(Connection conn) {
        this.conn = conn;
    }

    /** 设置提醒触发后的额外回调（文本为拼接好的提醒正文）。传 null 取消。 */
    public void setOnTaskRemind(java.util.function.Consumer<String> cb) {
        this.onTaskRemind = cb;
    }

    /**
     * 启动提醒服务。
     * @param delayMs 首次执行的延迟（毫秒）
     * @param periodMs 周期执行的间隔（毫秒）
     */
    public void start(long delayMs, long periodMs) {
        if (timer != null) {
            stop();
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ReminderService");
            t.setDaemon(true);
            return t;
        });
        // 先跑一次 check 再周期轮询
        timer.scheduleWithFixedDelay(this::check, delayMs, periodMs, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (timer != null) {
            timer.shutdown();
            timer = null;
        }
    }

    /**
     * 单轮扫描：查找需要提醒的任务。
     */
    public void check() {
        String sql = "SELECT id,title,note,remind_at,last_notified_at FROM tasks WHERE status!='removed' AND ( (remind_at IS NOT NULL AND remind_at<=?) OR (trigger_at IS NOT NULL AND trigger_at<=?) )";
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    String title = rs.getString("title");
                    String note = rs.getString("note");
                    long remindAt = rs.getLong("remind_at");
                    long lastNotified = rs.getLong("last_notified_at");
                    
                    // 不重复判定：last_notified_at IS NULL = 该提醒尚未通知 → 补发
                    // 已通知则跳过。这比"5分钟窗口"更贴合"重启补发且不重复"
                    if (lastNotified != 0) {
                        continue;
                    }
                    
                    String line = buildRemindText(title, note, remindAt);
                    // 本地弹窗 + 出声
                    localAlert(line);
                    // 触发后更新 last_notified_at，WHERE 保证并发下也只写一次
                    markNotified(id, now);
                    // 额外回调（如 QQ 推送），在标记成功后执行
                    java.util.function.Consumer<String> cb = onTaskRemind;
                    if (cb != null) {
                        try { cb.accept(line); } catch (Throwable ignore) {}
                    }
                }
            }
        } catch (Exception e) {
            // 静默处理错误，避免影响主程序
        }
    }

    private String buildRemindText(String title, String note, long remindAt) {
        String when = remindAt > 0
                ? java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                        .withZone(java.time.ZoneId.systemDefault())
                        .format(java.time.Instant.ofEpochMilli(remindAt)) : "到点";
        String line = "【任务提醒 · " + when + "】\n" + title;
        if (note != null && !note.isEmpty()) line += "\n备注：" + note;
        return line;
    }

    private void localAlert(String text) {
        // 弹窗在 EDT，整段 try/catch(Throwable) 兜底
        try {
            javax.swing.SwingUtilities.invokeLater(() -> {
                try {
                    JOptionPane.showMessageDialog(null, text, "任务提醒", JOptionPane.INFORMATION_MESSAGE);
                } catch (Throwable t) {
                    // 忽略弹窗失败
                }
            });
        } catch (Throwable t) {
            // 忽略 SwingUtilities 失败
        }
        try {
            Toolkit.getDefaultToolkit().beep();
        } catch (Throwable ignore) {}
    }

    private void markNotified(String id, long now) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE tasks SET last_notified_at=? WHERE id=? AND last_notified_at IS NULL")) {
            ps.setLong(1, now);
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (Exception e) {
            // 忽略更新失败
        }
    }
}