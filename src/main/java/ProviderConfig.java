import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * ProviderConfig —— AI 服务商配置（从 {@code nlp.properties} 读取）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>不写死任何厂商</b>：地址、密钥、模型名全部来自配置，换厂商只改配置不改代码；</li>
 *   <li><b>配置缺失不崩</b>：文件不存在时全部取空值，由 {@link #isConfigured()} 返回 false，
 *       上层据此判断「AI 能不能用」，而不是每次调用都报错（与 {@code QqBot} 的容错风格一致）；</li>
 *   <li>加载方式复用现有模式：{@code getClassLoader().getResourceAsStream(...)}。</li>
 * </ul>
 */
public class ProviderConfig {

    /** 配置文件资源名。 */
    public static final String RESOURCE = "nlp.properties";

    /** 模式：不使用 AI。 */
    public static final String MODE_OFF = "off";
    /** 模式：云端 API。 */
    public static final String MODE_CLOUD = "cloud";

    private String mode = MODE_OFF;
    private String provider = "";
    private String baseUrl = "";
    private String apiKey = "";
    private String model = "";
    private int timeoutSeconds = 20;
    private boolean verbose = true;

    /** 从 classpath 的 nlp.properties 读取；文件缺失时使用默认值（AI 关闭）。 */
    public static ProviderConfig load() {
        ProviderConfig cfg = new ProviderConfig();
        Properties p = new Properties();
        try (InputStream in = ProviderConfig.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                // 配置缺失 → 视为 AI 未启用，程序照常运行
                return cfg;
            }
            // 用 UTF-8 显式读取，并剥离 UTF-8 BOM：Windows 记事本另存为 UTF-8 会写入 BOM，
            // 那样第一个键会变成 "\ufeffmode"，导致 mode 读不到、静默退回 off —— 很难排查。
            String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
                text = text.substring(1);
            }
            p.load(new java.io.StringReader(text));
        } catch (IOException e) {
            // 读失败同样降级，不让配置问题影响主流程
            return cfg;
        }
        cfg.mode = p.getProperty("mode", MODE_OFF).trim().toLowerCase();
        cfg.provider = p.getProperty("provider", "").trim();
        cfg.baseUrl = p.getProperty("baseurl", "").trim();
        cfg.apiKey = p.getProperty("apikey", "").trim();
        cfg.model = p.getProperty("model", "").trim();
        cfg.verbose = !"0".equals(p.getProperty("verbose", "1").trim());
        String t = p.getProperty("timeout", "20").trim();
        try {
            int v = Integer.parseInt(t);
            // 超时必须有界：过小无意义，过大等于没设（S2-6）
            cfg.timeoutSeconds = Math.max(1, Math.min(v, 300));
        } catch (NumberFormatException e) {
            cfg.timeoutSeconds = 20;
        }
        return cfg;
    }

    /**
     * 密钥/地址/模型是否齐全。
     * <p>
     * 上层用它判断「AI 到底能不能用」，从而决定走 AI 还是提示用户手写。
     * <b>配置缺失不应表现为「每次调用都报错」。</b>
     */
    public boolean isConfigured() {
        return !baseUrl.isEmpty() && !apiKey.isEmpty() && !model.isEmpty();
    }

    /** 是否为云端模式。 */
    public boolean isCloud() {
        return MODE_CLOUD.equals(mode);
    }

    /** 是否完全不使用 AI（默认）。 */
    public boolean isOff() {
        return MODE_OFF.equals(mode) || mode.isEmpty();
    }

    /** 拼接 chat/completions 端点，容忍 baseurl 结尾是否带斜杠。 */
    public String chatCompletionsUrl() {
        String base = baseUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/chat/completions";
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    /** 调试用；<b>绝不打印 apiKey</b>。 */
    @Override
    public String toString() {
        return "ProviderConfig{mode=" + mode
                + ", provider=" + provider
                + ", baseUrl=" + baseUrl
                + ", model=" + model
                + ", apikey=" + (apiKey.isEmpty() ? "（未配置）" : "***已配置")
                + ", timeout=" + timeoutSeconds + "s}";
    }
}
