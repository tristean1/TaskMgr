import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * REQ-019 第 2 期（QQ 托管进守护进程）回归测试。
 *
 * <p><b>绝不联网、绝不碰真库</b>：本测试只验证「没网/没库的时候是不是给出了正确的答复」，
 * 因为这几条正是托管最容易出错、又最容易被手测漏掉的地方：
 * <ul>
 *   <li>没开就 {@code pushRemind} —— 必须是静默 false，不能抛（§6.5：提醒链路不能被 QQ 拖死）；</li>
 *   <li>库路径坏了 —— 必须明确报错并保持关闭，不能半开；</li>
 *   <li>{@code down} 重复调用 —— 幂等；</li>
 *   <li>{@code execute} 的用法/未知子命令 —— 与 {@code Main} 的旧行为一致。</li>
 * </ul>
 *
 * <p>锁文件落在 {@code user.home/.taskmgr}，所以跑本测试的 JVM 必须带
 * {@code -Duser.home=<临时目录>}（见 {@code test_timing.ps1}），否则会污染真用户目录。
 */
public class QqBotHostTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : ".");
        Files.createDirectories(outDir);

        Path home = Path.of(System.getProperty("user.home", "."));
        Files.createDirectories(home.resolve(".taskmgr"));

        List<String> lines = new ArrayList<>();
        try {
            statusAndUsage();
            pushWhenDown();
            downIsIdempotent();
            badDatabasePath();
        } catch (Throwable t) {
            failures.add("未捕获异常：" + t);
        }

        lines.add("PASS " + passed + " / FAIL " + failures.size());
        lines.addAll(failures);
        Files.writeString(outDir.resolve("qqbothost-results.txt"),
                String.join(System.lineSeparator(), lines) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        if (!failures.isEmpty()) System.exit(1);
    }

    /** 状态文本与用法：形状与 Main 的旧 qqbot 命令保持一致。 */
    private static void statusAndUsage() {
        QqBotHost h = new QqBotHost("unused.db", s -> { });
        try {
            check("初始状态 isUp=false", !h.isUp(), "");
            check("statusText 含「已关闭」", h.statusText().contains("已关闭"), h.statusText());
            check("statusText 含锁持有者一节", h.statusText().contains("单实例锁"), h.statusText());

            String usage = h.execute("qqbot");
            check("无参给用法", usage.contains("用法") && usage.contains("up"), usage);

            String bogus = h.execute("qqbot wrong");
            check("未知子命令有提示", bogus.contains("未知子命令"), bogus);

            String st = h.execute("qqbot status");
            check("execute(\"qqbot status\") 落到状态文本", st.contains("已关闭"), st);

            // 前缀剥离：带不带 qqbot 前缀都应走到同一套分发
            String noPrefix = h.execute("up-not-a-real-sub");
            check("无前缀也按子命令分发", noPrefix.contains("未知子命令"), noPrefix);
        } finally {
            h.close();
        }
    }

    /** 没开推送时必须静默返回 false —— 提醒线程不能被 QQ 的状态影响。 */
    private static void pushWhenDown() {
        QqBotHost h = new QqBotHost("unused.db", s -> { });
        try {
            check("未开启 pushRemind 返回 false", !h.pushRemind("到点提醒：测试"), "");
            check("未开启 pushRemind 不改变状态", !h.isUp(), "");
        } finally {
            h.close();
        }
    }

    /** down 幂等：第一次与第二次都应是"本就处于关闭状态"，且不抛异常。 */
    private static void downIsIdempotent() {
        QqBotHost h = new QqBotHost("unused.db", s -> { });
        try {
            String d1 = h.execute("qqbot down");
            String d2 = h.execute("qqbot down");
            check("down 幂等（第一次）", d1.contains("本就处于关闭状态"), d1);
            check("down 幂等（第二次）", d2.contains("本就处于关闭状态"), d2);
        } finally {
            h.close();
        }
    }

    /** 库路径不可用：必须明确报错、保持关闭，且把锁还回去（否则下次永远抢不到）。 */
    private static void badDatabasePath() {
        File bogus = new File(System.getProperty("java.io.tmpdir"),
                "tm-qqbot-nope-" + System.nanoTime() + File.separator + "tasks.db");
        QqBotHost h = new QqBotHost(bogus.getPath(), s -> { });
        try {
            String up = h.execute("qqbot up");
            boolean saidSomething = up.contains("启动失败")
                    || up.contains("初始化失败")
                    || up.contains("单实例锁");
            check("坏库路径 up 给出明确原因", saidSomething, up);
            check("坏库路径 up 后仍未开启", !h.isUp(), up);

            // 锁必须已归还：再一次 up 若报"已有另一个实例"，说明上一次把锁漏掉了
            String again = h.execute("qqbot up");
            check("失败后不残留单实例锁", !again.contains("已有另一个"), again);
            check("失败后 pushRemind 仍是 false", !h.pushRemind("x"), "");
        } finally {
            h.close();
        }
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
        } else {
            failures.add("[FAIL] " + name + " ｜ 实际输出：" + detail);
        }
    }
}
