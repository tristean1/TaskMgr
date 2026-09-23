import java.sql.Connection;
import java.sql.DriverManager;

/**
 * 自启守护入口。
 * 开机自启动拉起的进程：无交互，只弹提醒。
 */
public class ReminderMain {
    public static void main(String[] args) {
        try {
            Class.forName("org.sqlite.JDBC");
            
            // 连接到用户主目录下的数据库
            String dbPath = System.getProperty("user.home")
                    + java.io.File.separator + ".taskmgr"
                    + java.io.File.separator + "tasks.db";
            java.io.File dbFile = new java.io.File(dbPath);
            if (!dbFile.exists()) {
                System.err.println("数据库文件不存在: " + dbPath);
                return;
            }
            
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                // 启动提醒服务：5秒后开始，每10秒检查一次
                ReminderService reminder = new ReminderService(conn);
                reminder.start(5000, 10_000L);
                
                System.out.println("提醒守护进程已启动，监听数据库: " + dbPath);
                System.out.println("按 Ctrl+C 终止程序");
                
                // 阻塞主线程保持进程常驻
                Thread.currentThread().join();
            }
        } catch (Exception e) {
            System.err.println("守护进程启动失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}