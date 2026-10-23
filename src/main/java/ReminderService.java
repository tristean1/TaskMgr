import javax.swing.JOptionPane;
import java.awt.Toolkit;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 独立提醒服务，从 QqBot 提炼提醒最小逻辑并独立，不依赖 QQ。
 * 扫描数据库中的任务，到达提醒时间时弹窗+beep。
 * <p><b>BUG-027 / 附六 §6.5（2026-10-21 修复）</b>：原来的顺序是「弹窗 → 写 last_notified_at → QQ 推送」，
 * 推送失败的提醒会被永久烧掉（桌面收到、手机永远收不到）。现在改为：
 * 弹窗（每个任务一次）→ 推送 → <b>推送成功才写 last_notified_at</b>；失败保留未提醒状态并按
 * {@link ReminderPacing} 节流重试。
 */
public class ReminderService {
    private final Connection conn;            // 外部传入，调用方负责 open/close
    private ScheduledExecutorService timer;
    /** 到点提醒后的推送回调：返回 true 表示推送成功（才允许标记已提醒）。可为 null（=无需推送）。 */
    private volatile Function<String, Boolean> onTaskRemindPush;
    /** 本地提醒通道；测试/无头环境可注入无声实现。 */
    private volatile Consumer<String> alerter = this::localAlert;
    /** 日志回调；默认静默（保持原有「不影响主流程」的习惯），由调用方决定是否接入。 */
    private volatile Consumer<String> logger = s -> {};
    /** 重试/弹窗节流（BUG-027）。 */
    private final ReminderPacing pacing = new ReminderPacing();
    private volatile Runnable onTick = () -> {};

    public ReminderService(Connection conn) {
        this.conn = conn;
    }

    /** 设置提醒触发后的推送回调（返回是否成功）。传 null 取消。 */
    public void setOnTaskRemind(Function<String, Boolean> cb) {
        this.onTaskRemindPush = cb;
    }

    /** 注入本地提醒实现（默认 Swing 弹窗 + beep；测试可传 no-op）。 */
    public void setAlerter(Consumer<String> alerter) {
        this.alerter = (alerter == null) ? this::localAlert : alerter;
    }

    /** 注入日志回调（可选）。 */
    public void setLogger(Consumer<String> logger) {
        this.logger = (logger == null) ? s -> {} : logger;
    }

    public void setOnTick(Runnable tick) { onTick = tick == null ? () -> {} : tick; }

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
        onTick.run();
        synchronized (conn) { checkReminders(); }
    }

    private void checkReminders() {
        // BUG-007（2026-10-19 修复）：必须排除 done。
        // 用 NOT IN 而不是 ='todo'，是为了将来 ongoing 类型加进来时不必再改这一行。
        String sql = "SELECT id,title,note,remind_at,last_notified_at FROM tasks "
                + "WHERE status NOT IN ('done','removed') "
                + "AND ( (remind_at IS NOT NULL AND remind_at<=?) OR (trigger_at IS NOT NULL AND trigger_at<=?) )";
        long now = System.currentTimeMillis();
        Set<String> seen = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    seen.add(id);
                    long lastNotified = rs.getLong("last_notified_at");
                    // 不重复判定：last_notified_at 非 0 = 已通知 → 跳过
                    if (lastNotified != 0) continue;
                    // BUG-027：推送失败的提醒按退避重试，不每 10 秒刷屏
                    if (!pacing.shouldAttempt(id, now)) continue;

                    String title = rs.getString("title");
                    String note = rs.getString("note");
                    long remindAt = rs.getLong("remind_at");
                    String line = buildRemindText(title, note, remindAt);
                    // 本地弹窗：每个任务只弹一次（重试推送时不再重复打扰）
                    if (pacing.markAlerted(id)) alerter.accept(line);

                    // 推送（如配置）：成功才允许标记已提醒（BUG-027 / §6.5）
                    Function<String, Boolean> push = onTaskRemindPush;
                    boolean delivered;
                    if (push == null) {
                        delivered = true;
                    } else {
                        try {
                            Boolean r = push.apply(line);
                            delivered = (r == null) || r;
                        } catch (Throwable t) {
                            logger.accept("[提醒] 推送异常: " + t.getMessage());
                            delivered = false;
                        }
                    }

                    if (delivered && markNotified(id, now)) {
                        pacing.onSuccess(id);
                    } else {
                        pacing.onFailure(id, now);
                        logger.accept("[提醒] 推送/标记失败，保留未提醒状态稍后重试: " + title);
                    }
                }
            }
            pacing.retain(seen);
        } catch (Exception e) {
            // 不静默：交给注入的 logger（默认 no-op，保持「不影响主程序」）
            logger.accept("[提醒] 扫描失败: " + e.getMessage());
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

    /** 更新 last_notified_at；返回是否真的写入（行数=1，幂等）。 */
    private boolean markNotified(String id, long now) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE tasks SET last_notified_at=? WHERE id=? AND last_notified_at IS NULL")) {
            ps.setLong(1, now);
            ps.setString(2, id);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            logger.accept("[提醒] 记录通知失败: " + e.getMessage());
            return false;
        }
    }
}
