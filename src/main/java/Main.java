import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Scanner;
import java.nio.charset.StandardCharsets;

/**
 * TaskManager — 效率工具（任务管理 + DDL 提醒）
 * <p>
 * 任务类命令（add / list / done / remove / init / help）统一交由 {@link TaskEngine} 解析；
 * 本类只负责：主循环、提醒服务、以及独有的 {autostart, qqbot} 控制命令。
 */
public class Main {

    /**
     * QQ 机器人托管（第 2 期起与守护进程共用同一份实现 {@link QqBotHost}）。
     * 懒创建：{@code qqbot up} 时才初始化，避免不用 QQ 的人也付一份连接代价。
     */
    private static QqBotHost qqHost;

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
            engine.timingTick(System.out::println);

            // 启动提醒服务：先立刻补发一次, 之后每10秒
            ReminderService reminder = new ReminderService(conn);
            reminder.setLogger(System.out::println);
            reminder.setOnTick(() -> engine.timingTick(message -> {
                System.out.println(message);
                if (qqHost != null) qqHost.pushRemind(message);
            }));
            // 本地弹窗通道：无头环境或 settings.properties 里 local.popup=0 时为空实现
            reminder.setAlerter(LocalAlert.alerter());
            reminder.start(0, 10_000L);

            // REQ-021：每日问候（控制台模式下同样生效；没有 QQ 时发不出去，会重试到 12:00）
            HolidayCalendar holidays = HolidayCalendar.atHome(System.out::println);
            GreetingService greeting = new GreetingService(engine, holidays,
                    java.time.Clock.systemDefaultZone(), System.out::println);
            greeting.setPush(() -> qqHost != null && qqHost.isUp() ? qqHost::pushRemind : null);
            engine.setGreetPusher(text -> qqHost != null && qqHost.isUp() && qqHost.pushRemind(text));
            greeting.start();
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

            Thread timingShutdown = new Thread(() -> engine.shutdownTiming(System.out::println), "timing-shutdown");
            Runtime.getRuntime().addShutdownHook(timingShutdown);
            try {
            Scanner sc = commandScanner();
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
            } finally {
                reminder.stop();
                icsTimer.shutdownNow();
                greeting.close();
                if (qqHost != null) qqHost.close();
                engine.shutdownTiming(System.out::println);
                try { Runtime.getRuntime().removeShutdownHook(timingShutdown); }
                catch (IllegalStateException ignored) { }
            }
        }
    }

    /** 控制台字符集独立于 file.encoding；管道/文件输入约定为 UTF-8。 */
    static Scanner commandScanner() {
        var console = System.console();
        return console != null
                ? new Scanner(console.reader())
                : new Scanner(System.in, StandardCharsets.UTF_8);
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
     * 实现全部委托给 {@link QqBotHost}——守护进程里的 {@code task qqbot} 走的是同一份代码，
     * 免得"控制台能开、后台开不了"这种漂移。
     */
    private static void handleQqbot(ReminderService reminder, String[] argv) {
        if (qqHost == null) {
            qqHost = new QqBotHost(dbPath(), System.out::println);
        }
        StringBuilder cmd = new StringBuilder("qqbot");
        for (String a : argv) cmd.append(' ').append(a);
        System.out.println(qqHost.execute(cmd.toString()));
        // 提醒要不要往 QQ 推，跟着 QQ 的开关走：开起来就挂回调，关下去就摘掉。
        // （三元里放方法引用推断不出目标类型，只能拆成两句。）
        if (qqHost.isUp()) {
            reminder.setOnTaskRemind(qqHost::pushRemind);
        } else {
            reminder.setOnTaskRemind(null);
        }
    }
}
