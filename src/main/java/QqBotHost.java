import java.sql.Connection;
import java.sql.DriverManager;
import java.util.function.Consumer;

/**
 * QqBotHost —— 在任意宿主进程里托管一个 {@link QqBot}（REQ-019 第 2 期）。
 *
 * <p><b>为什么要有它</b>：{@code qqbot up/down} 原本只写在 {@link Main} 的静态字段里。
 * 第 2 期要让<b>守护进程</b>也能托管 QQ，如果两处各写一遍必然漂移——
 * 这里恰好是最容易漂移的三件事：BUG-001 的跨进程单实例锁、QQ 专用的独立数据库连接、
 * 以及「到期提醒要不要往 QQ 推」的回调挂接。
 *
 * <p><b>调用方只提供两样东西</b>：库路径、一行文本往哪送（控制台 {@code System.out::println}
 * 或守护进程的事件流）。至于 QQ 通不通，全在这里判。
 *
 * <p><b>线程</b>：{@link #up}/{@link #down}/{@link #close} 互斥；
 * {@link #pushRemind} 会被提醒线程调用，只读 {@code isUp()} 不抢锁——
 * 抢锁会把提醒线程卡在 QQ 网络握手上，那会让提醒本身迟到。
 */
public class QqBotHost {

    private final String dbPath;
    private final Consumer<String> sink;

    private volatile QqBot qqBot;
    private Connection qqConn;
    private SingleInstanceLock lock;

    public QqBotHost(String dbPath, Consumer<String> sink) {
        this.dbPath = dbPath;
        this.sink = (sink != null) ? sink : s -> { };
    }

    /** 消息收发是否已开启。 */
    public boolean isUp() {
        QqBot bot = qqBot;
        return bot != null && bot.isUp();
    }

    /**
     * 开启消息收发（幂等）。
     *
     * @return true 表示当前确实处于开启状态
     */
    public boolean up() {
        return up(sink);
    }

    /** 关闭消息收发（幂等）。 */
    public void down() {
        down(sink);
    }

    /** 状态文本（多行）。 */
    public String statusText() {
        StringBuilder sb = new StringBuilder();
        sb.append(isUp() ? "QQ 消息功能: 已开启" : "QQ 消息功能: 已关闭");
        sb.append(System.lineSeparator()).append("单实例锁(qqbot): ")
                .append(SingleInstanceLock.describeHolder("qqbot"));
        return sb.toString();
    }

    /**
     * 提醒线程用的推送口：没开就静默丢弃。
     * 不抛异常——提醒链路绝不能因为 QQ 的问题而中断（§6.5 的教训）。
     *
     * <p>返回值必须跟 {@code QqBot#pushRemind} 一致（{@code boolean}）：
     * {@code ReminderService.setOnTaskRemind} 收的就是"String → Boolean"这个形状，
     * 写成 void 会编译不过（方法引用 void 无法转 Boolean）。
     *
     * @return 是否真的推到了 QQ
     */
    public boolean pushRemind(String text) {
        try {
            if (isUp()) return qqBot.pushRemind(text);
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 执行一条 {@code qqbot ...} 命令，返回要回给用户的文本。
     * 空参数返回用法；未知子命令返回提示（不抛异常）。
     */
    public String execute(String line) {
        String s = (line == null) ? "" : line.trim();
        if (s.toLowerCase().startsWith("qqbot")) s = s.substring(5).trim();
        String[] argv = s.isEmpty() ? new String[0] : s.split("\\s+");

        StringBuilder sb = new StringBuilder();
        Consumer<String> out = t -> {
            if (sb.length() > 0) sb.append(System.lineSeparator());
            sb.append(t);
        };

        if (argv.length == 0) {
            out.accept("用法: qqbot up|down|status");
            return sb.toString();
        }
        switch (argv[0].toLowerCase()) {
            case "up":
                up(out);
                break;
            case "down":
                down(out);
                break;
            case "status":
                out.accept(statusText());
                break;
            default:
                out.accept("未知子命令: " + argv[0] + "，可用: up|down|status");
        }
        return sb.toString();
    }

    /** 进程退出前调用：关连接、放锁。幂等。 */
    public synchronized void close() {
        if (qqBot != null && qqBot.isUp()) {
            try {
                qqBot.stopMessaging();
            } catch (Throwable ignored) {
            }
        }
        if (lock != null) {
            lock.release();
            lock = null;
        }
        if (qqConn != null) {
            try {
                qqConn.close();
            } catch (Throwable ignored) {
            }
            qqConn = null;
        }
    }

    // ---------------------------------------------------------------- 内部

    private synchronized boolean up(Consumer<String> out) {
        // 同一进程内重复 up：先于抢锁判断，否则同 JVM 抢锁会拿到 null 而误报"别的实例"。
        if (isUp()) {
            out.accept("[QQBot] 消息功能已在运行（如需重开请先 qqbot down）");
            return true;
        }

        // BUG-001：跨进程单实例保护。本进程内的 qqBot != null 只能防"同进程连点两次"，
        // 防不住"另一个 JVM 也在跑 QqBot"——那才是实测中连接互踢的元凶。
        SingleInstanceLock newLock;
        try {
            newLock = SingleInstanceLock.acquire("qqbot", "QqBotHost.up");
        } catch (Exception e) {
            out.accept("[QQBot] 单实例锁创建失败: " + e.getMessage());
            return false;
        }
        if (newLock == null) {
            String sep = System.lineSeparator();
            out.accept("[QQBot] 已有另一个 QqBot 实例在运行，本次开启已取消。"
                    + sep + "        当前锁持有者："
                    + sep + "        " + SingleInstanceLock.describeHolder("qqbot").replace(sep, sep + "        ")
                    + sep + "        若确认没有实例在跑，可删除 ~/.taskmgr/qqbot.lock 后重试。");
            return false;
        }

        try {
            if (qqBot == null) {
                Class.forName("org.sqlite.JDBC");
                qqConn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                qqConn.setAutoCommit(true);
                qqBot = new QqBot(new TaskEngine(qqConn));
                qqBot.loadConfig();
            }
            if (qqBot.isUp()) {
                out.accept("[QQBot] 消息功能已在运行");
                newLock.release();          // 已在运行 → 归还锁，别自己占着不放
                return true;
            }
            qqBot.startMessaging();
            lock = newLock;                 // 锁由本宿主持有，直到 down/close
            out.accept("[QQBot] 已开启，并已接入到期提醒的 QQ 推送");
            return true;
        } catch (Exception e) {
            out.accept("[QQBot] 启动失败: " + e.getMessage());
            newLock.release();
            return false;
        }
    }

    private synchronized void down(Consumer<String> out) {
        if (qqBot != null && qqBot.isUp()) {
            qqBot.stopMessaging();
            out.accept("[QQBot] 消息收发已关闭");
        } else {
            out.accept("[QQBot] 消息收发本就处于关闭状态");
        }
        // BUG-001：关闭时归还单实例锁，让其他启动方式能接管
        if (lock != null) {
            lock.release();
            lock = null;
        }
    }
}
