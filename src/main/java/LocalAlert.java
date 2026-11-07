import java.awt.GraphicsEnvironment;
import java.util.function.Consumer;

/**
 * LocalAlert —— 桌面弹窗 + 蜂鸣这一条「本地提醒通道」，以及它的开关。
 *
 * <p><b>为什么要单独拎出来（2026-11-06）</b>：项目要挪到云服务器上跑，服务器没有桌面，
 * 提醒只剩 QQ 一条通道。原先弹窗逻辑分别写在 {@link ReminderService#localAlert} 和
 * {@code QqBot#localAlert} 里，两处各有一份 try/catch；服务器上每一轮扫描都会构造一次 Swing
 * 调用再被 {@code HeadlessException} 打回来——虽然被吞掉不致命，但既浪费又误导日志。
 *
 * <p>开关读 {@code ~/.taskmgr/settings.properties} 的 {@code local.popup}（默认开），
 * 另外**无头环境自动关**：即使配置文件写漏了，服务器上也不会去碰 Swing。
 * 判定用 {@link GraphicsEnvironment#isHeadless()}，它同时覆盖
 * {@code -Djava.awt.headless=true} 和「Linux 上没有 DISPLAY」两种情形。
 */
public final class LocalAlert {

    /** 设置键：是否启用桌面弹窗（{@code 1} 开 / {@code 0} 关）。 */
    public static final String KEY_POPUP = "local.popup";

    private LocalAlert() {
    }

    /** 当前是否应该弹窗：无头环境一律不弹，其余看设置（默认弹）。 */
    public static boolean enabled() {
        if (GraphicsEnvironment.isHeadless()) return false;
        return AppSettings.getBool(KEY_POPUP, true);
    }

    /** 弹一次（无头或已关闭时是空操作）。整段 try/catch 兜底，绝不因提醒本身搞崩主流程。 */
    public static void show(String text) {
        if (!enabled()) return;
        try {
            javax.swing.SwingUtilities.invokeLater(() -> {
                try {
                    javax.swing.JOptionPane.showMessageDialog(
                            null, text, "任务提醒", javax.swing.JOptionPane.INFORMATION_MESSAGE);
                } catch (Throwable ignore) {
                    // 弹窗失败不影响提醒本身的其余环节
                }
            });
        } catch (Throwable ignore) {
            // SwingUtilities 都失败时同样忽略
        }
        try {
            java.awt.Toolkit.getDefaultToolkit().beep();
        } catch (Throwable ignore) {
        }
    }

    /**
     * 给 {@link ReminderService#setAlerter} 用的实现：关闭时返回**空实现**，
     * 这样连「进 EDT 再抛异常」这一步都省了。
     */
    public static Consumer<String> alerter() {
        if (!enabled()) return text -> { };
        return LocalAlert::show;
    }
}
