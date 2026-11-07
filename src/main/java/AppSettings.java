import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * AppSettings —— {@code ~/.taskmgr/settings.properties} 的极简读取器。
 *
 * <p>为什么另起一个文件、而不是塞进 {@code nlp.properties} / {@code qqbot.properties}：
 * 那两份分别是「AI 解析」和「QQ 接入」的配置，各自会被 {@code ProviderConfig} 按自己的规则读；
 * 而这里的开关（比如要不要弹窗）属于**程序行为**，跟 AI、QQ 都没关系，混进去会让两边的边界变糊。
 *
 * <p>格式：{@code key=value}，一行一条，{@code #} 或 {@code !} 开头为注释；UTF-8 无 BOM 最好，
 * 带 BOM 也能读（{@code ProviderConfig} 那边踩过这个坑，这里一并剥掉）。
 *
 * <p><b>读不到文件不是错误</b>：所有键都有默认值，文件缺失/损坏时静默回落到默认，
 * 绝不因为一个可选的设置文件让程序起不来。
 */
public final class AppSettings {

    /** 文件名（放在 {@code ~/.taskmgr/} 下，与数据库、日志同级）。 */
    public static final String FILE_NAME = "settings.properties";

    /** 缓存：解析一次就够（改文件需重启进程才生效，个人自用可以接受）。 */
    private static volatile Map<String, String> cache;

    private AppSettings() {
    }

    /** 设置文件路径（受 {@code -Dtaskmgr.home} 影响，测试可指向临时目录）。 */
    public static Path path() {
        return DaemonEndpoint.home().resolve(FILE_NAME);
    }

    /** 丢掉缓存，下次读取重新解析（测试用；线上改文件需重启进程）。 */
    public static void reload() {
        cache = null;
    }

    private static Map<String, String> load() {
        Map<String, String> hit = cache;
        if (hit != null) return hit;
        synchronized (AppSettings.class) {
            if (cache != null) return cache;
            Map<String, String> map = new HashMap<>();
            try {
                Path p = path();
                if (Files.exists(p)) {
                    String text = Files.readString(p, StandardCharsets.UTF_8);
                    if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
                    for (String raw : text.split("\\R")) {
                        String line = raw.trim();
                        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
                        int eq = line.indexOf('=');
                        if (eq <= 0) continue;
                        map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // 设置文件是可选件：读不了就全用默认值
            }
            cache = map;
            return map;
        }
    }

    /** 取字符串；键不存在或值为空时返回 {@code def}。 */
    public static String get(String key, String def) {
        String v = load().get(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    /** 取布尔；认 {@code 1/0}、{@code true/false}、{@code yes/no}、{@code on/off}、{@code 开/关}，其余回落默认。 */
    public static boolean getBool(String key, boolean def) {
        String v = load().get(key);
        if (v == null || v.isEmpty()) return def;
        return switch (v.trim().toLowerCase()) {
            case "1", "true", "yes", "on", "是", "开" -> true;
            case "0", "false", "no", "off", "否", "关" -> false;
            default -> def;
        };
    }

    /**
     * 取整数；不是数字或越界（&lt; min 或 &gt; 65535 对端口无意义）时回落到默认。
     * 只给 {@code web.port} 这类需要数字的设置用。
     */
    public static int getInt(String key, int def) {
        String v = load().get(key);
        if (v == null || v.isEmpty()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * 写一个键（保留文件里其它行与注释）。
     *
     * <p>写成功后立刻清缓存，所以 {@code greet 7:30} 这种命令**当场生效**，不必重启进程。
     * 文件不存在时新建；出现 IO 错误抛出（由调用方翻成一句人话，而不是静默失败）。
     */
    public static synchronized void set(String key, String value) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("键不能为空");
        Path p = path();
        StringBuilder sb = new StringBuilder();
        boolean replaced = false;
        try {
            if (Files.exists(p)) {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
                for (String raw : text.split("\\R")) {
                    String line = raw.trim();
                    if (!line.isEmpty() && !line.startsWith("#") && !line.startsWith("!")) {
                        int eq = line.indexOf('=');
                        if (eq > 0 && line.substring(0, eq).trim().equals(key)) {
                            sb.append(key).append('=').append(value).append('\n');
                            replaced = true;
                            continue;   // 后面的同名行也一并改写，避免出现两条互相打架
                        }
                    }
                    sb.append(raw).append('\n');
                }
            }
            if (!replaced) sb.append(key).append('=').append(value).append('\n');
            Path parent = p.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写设置文件失败：" + p, e);
        }
        cache = null;
    }

    /** 删一个键（不存在的键当成功）。 */
    public static synchronized void remove(String key) {
        Path p = path();
        if (!Files.exists(p)) { cache = null; return; }
        StringBuilder sb = new StringBuilder();
        try {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
            for (String raw : text.split("\\R")) {
                String line = raw.trim();
                if (!line.isEmpty() && !line.startsWith("#") && !line.startsWith("!")) {
                    int eq = line.indexOf('=');
                    if (eq > 0 && line.substring(0, eq).trim().equals(key)) continue;
                }
                sb.append(raw).append('\n');
            }
            Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写设置文件失败：" + p, e);
        }
        cache = null;
    }
}
