import java.util.prefs.Preferences;

/**
 * 开机自启动管理（Windows 注册表实现）。
 * 使用 java.util.prefs.Preferences.userRoot().node("TaskMgr") 映射到
 * HKCU\Software\JavaSoft\Prefs\TaskMgr\Run（Windows 开机自启即读写该注册表 Run 位置）。
 */
public class Autostart {
    private static final Preferences PREFS = Preferences.userRoot().node("TaskMgr");
    private static final String RUN_KEY = "Run";
    
    /**
     * 启用开机自启动。
     * @param command 完整启动命令，指向 ReminderMain 的启动脚本/批处理
     */
    public static void enable(String command) {
        PREFS.put(RUN_KEY, command);
        try {
            PREFS.flush(); // 确保写入注册表
        } catch (Exception e) {
            // 忽略刷新失败
        }
    }
    
    /**
     * 禁用开机自启动。
     */
    public static void disable() {
        PREFS.remove(RUN_KEY);
        try {
            PREFS.flush();
        } catch (Exception e) {
            // 忽略刷新失败
        }
    }
    
    /**
     * 检查是否已启用开机自启动。
     * @return true 如果已启用
     */
    public static boolean isEnabled() {
        return !PREFS.get(RUN_KEY, "").isEmpty();
    }
    
    /**
     * 获取当前设置的自启动命令。
     * @return 自启动命令，如果未设置则返回空字符串
     */
    public static String getCommand() {
        return PREFS.get(RUN_KEY, "");
    }
}