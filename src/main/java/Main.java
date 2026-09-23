import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Scanner;

/**
 * TaskManager — 效率工具（任务管理 + DDL 提醒）
 * <p>
 * 任务类命令（add / list / done / remove / init / help）统一交由 {@link TaskEngine} 解析；
 * 本类只负责：主循环、提醒服务、以及独有的 {autostart, qqbot} 控制命令。
 */
public class Main {

    /** 懒创建的 QQ 机器人（qqbot up 时初始化）。 */
    private static QqBot qqBot;
    /** qqbot 使用的独立数据库连接（与主循环连接分离，避免线程并发复用）。 */
    private static Connection qqConn;

    private static String dbPath() {
        return System.getProperty("user.home")
                + java.io.File.separator + ".taskmgr"
                + java.io.File.separator + "tasks.db";
    }

    public static void main(String[] args) throws Exception {
        var dbFile = new java.io.File(dbPath());
        if (dbFile.getParentFile() != null) dbFile.getParentFile().mkdirs();
        System.out.println("[DB] " + dbFile.getAbsolutePath());

        Class.forName("org.sqlite.JDBC");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath())) {
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL;");
                st.execute("PRAGMA busy_timeout=3000;");
            }

            // 命令解析统一入口（构造时幂等建表）
            TaskEngine engine = new TaskEngine(conn);
            engine.setOutput(System.out::println);

            // 启动提醒服务：先立刻补发一次, 之后每10秒
            ReminderService reminder = new ReminderService(conn);
            reminder.start(0, 10_000L);
// 每日自动导出 .ics（手机兜底）：启动时先导一次，之后每 6 小时重导（幂等，容错覆盖“每天至少一次”）
            java.util.concurrent.ScheduledExecutorService icsTimer =
                    java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "ics-exporter");
                        t.setDaemon(true);
                        return t;
                    });
            icsTimer.scheduleWithFixedDelay(() -> {
                try {
                    engine.exportIcs();
                } catch (Throwable ignored) { }
            }, 0, 6 * 60 * 60 * 1000L, java.util.concurrent.TimeUnit.MILLISECONDS);

            // 用系统/控制台默认编码读取输入（Java 17 下默认编码跟随控制台代码页）
            Scanner sc = new Scanner(System.in);
            System.out.println("TaskManager. 输入 help 查看用法");
            while (true) {
                System.out.print("> ");
                if (!sc.hasNextLine()) break;
                String line = sc.nextLine().trim();
                if (line.isEmpty()) continue;
                String[] tokens = line.split("\\s+");
                String cmd = tokens[0].toLowerCase();
                String[] argv = (tokens.length > 1)
                        ? java.util.Arrays.copyOfRange(tokens, 1, tokens.length) : new String[0];

                // autostart / qqbot 为控制台专属命令，Main 处理；其余交给 TaskEngine
                switch (cmd) {
                    case "autostart":
                        handleAutostart(argv);
                        break;
                    case "qqbot":
                        handleQqbot(reminder, argv);
                        break;
                    case "exit":
                    case "quit":
                        System.out.println("bye.");
                        return;
                    default:
                        engine.execute(line);
                }
            }
        }
    }

    /**
     * 处理 autostart 命令：on|off|status
     * 映射到 Autostart；on 时命令指向 ReminderMain。
     */
    private static void handleAutostart(String[] argv) {
        if (argv.length == 0) {
            System.out.println("用法: autostart on|off|status");
            return;
        }

        String subcmd = argv[0].toLowerCase();
        switch (subcmd) {
            case "on":
                String javaHome = System.getProperty("java.home");
                String javaExe = javaHome + "\\bin\\java.exe";
                String classpath = "target\\classes";
                String mainClass = "ReminderMain";
                String command = "\"" + javaExe + "\" -cp \"" + classpath + "\" " + mainClass;

                Autostart.enable(command);
                System.out.println("已启用开机自启动");
                System.out.println("命令: " + command);
                break;

            case "off":
                Autostart.disable();
                System.out.println("已禁用开机自启动");
                break;

            case "status":
                if (Autostart.isEnabled()) {
                    System.out.println("开机自启动: 已启用");
                    System.out.println("命令: " + Autostart.getCommand());
                } else {
                    System.out.println("开机自启动: 已禁用");
                }
                break;

            default:
                System.out.println("未知子命令: " + subcmd + "，可用: on|off|status");
        }
    }

    /**
     * 处理 qqbot 命令：up|down|status
     * 在运行中的主命令行里开关 QQ 消息功能（收发 + 到期提醒的 QQ 推送）。
     */
    private static void handleQqbot(ReminderService reminder, String[] argv) {
        if (argv.length == 0) {
            System.out.println("用法: qqbot up|down|status");
            return;
        }
        String subcmd = argv[0].toLowerCase();
        switch (subcmd) {
            case "up":
                qqbotUp(reminder);
                break;
            case "down":
                qqbotDown(reminder);
                break;
            case "status":
                System.out.println(qqBot != null && qqBot.isUp()
                        ? "QQ 消息功能: 已开启"
                        : "QQ 消息功能: 已关闭");
                break;
            default:
                System.out.println("未知子命令: " + subcmd + "，可用: up|down|status");
        }
    }

    /** qqbot up：懒初始化机器人并开启消息收发，同时挂上提醒推送回调。 */
    private static void qqbotUp(ReminderService reminder) {
        if (qqBot == null) {
            try {
                Class.forName("org.sqlite.JDBC");
                qqConn = DriverManager.getConnection("jdbc:sqlite:" + dbPath());
                qqConn.setAutoCommit(true);
                qqBot = new QqBot(new TaskEngine(qqConn));
                qqBot.loadConfig();
            } catch (Exception e) {
                System.out.println("[QQBot] 初始化失败: " + e.getMessage());
                return;
            }
        }
        if (qqBot.isUp()) {
            System.out.println("[QQBot] 消息功能已在运行");
            return;
        }
        try {
            qqBot.startMessaging();
            reminder.setOnTaskRemind(qqBot::pushRemind);
            System.out.println("[QQBot] 已开启，并已接入到期提醒的 QQ 推送");
        } catch (Exception e) {
            System.out.println("[QQBot] 启动失败: " + e.getMessage());
        }
    }

    /** qqbot down：关闭 QQ 消息收发，并解除提醒推送回调。 */
    private static void qqbotDown(ReminderService reminder) {
        reminder.setOnTaskRemind(null);
        if (qqBot != null) {
            qqBot.stopMessaging();
        } else {
            System.out.println("[QQBot] 消息收发本就处于关闭状态");
        }
    }
}
