import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * CloudNlParser —— 通过「OpenAI 兼容的 /chat/completions 协议」调用云端模型，把一句话解析成 {@link ParsedTask}。
 * <p>
 * <b>不写死任何厂商</b>：地址、密钥、模型名全部来自 {@link ProviderConfig}（{@code nlp.properties}）。
 * 用 JDK 自带 {@link HttpClient}，不引入新依赖。
 * <p>
 * 职责边界：本类只负责「拿到原始结果」，<b>不做校验</b>（校验在 {@link ParsedTaskValidator}，
 * 这样换模型后校验不会失效）。
 * <p>
 * 难点不在发 HTTP，而在「模型返回的东西不总是你想要的格式」，因此对四层失败分别处理：
 * <ol>
 *   <li>L1 HTTP 状态码非 2xx → 报错并带上响应体；</li>
 *   <li>L2 响应不是合法 JSON → 报错并带上原文片段；</li>
 *   <li>L3 {@code choices[0].message.content} 缺失 → 报错；</li>
 *   <li>L4 {@code content} 里不是纯 JSON → 容错提取首个 {@code {} 到末个 {@code }} 的子串。</li>
 * </ol>
 */
public class CloudNlParser implements NlParser {

    private final ProviderConfig cfg;
    private final HttpClient http;
    private final Clock clock;
    /** 日志回调；默认打到标准输出。 */
    private final java.util.function.Consumer<String> log;

    public CloudNlParser() {
        this(ProviderConfig.load(), Clock.systemDefaultZone(), System.out::println);
    }

    /** 允许注入配置，便于测试。 */
    public CloudNlParser(ProviderConfig cfg) {
        this(cfg, Clock.systemDefaultZone(), System.out::println);
    }

    /** 允许注入 {@link Clock} 以便测试固定「现在」（与 {@link IcsExporter} 风格一致）。 */
    public CloudNlParser(ProviderConfig cfg, Clock clock, java.util.function.Consumer<String> log) {
        this.cfg = cfg == null ? ProviderConfig.load() : cfg;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
        this.log = log == null ? s -> { } : log;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(this.cfg.getTimeoutSeconds()))
                .build();
    }

    /** 密钥/地址/模型是否齐全。上层据此决定走 AI 还是提示用户手写。 */
    public boolean isConfigured() {
        return cfg.isConfigured();
    }

    public ProviderConfig getConfig() {
        return cfg;
    }

    @Override
    public ParsedTask parse(String text) throws NlParseException {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        if (!cfg.isConfigured()) {
            // 失败不静默：明确告诉调用方缺什么
            throw new NlParseException("AI 未配置完整（需要 baseurl / apikey / model），请检查 " + ProviderConfig.RESOURCE);
        }

        String requestBody = buildRequestBody(text.trim());
        long startedAt = System.currentTimeMillis();
        // 可观测：调用时间 + 输入原文
        log.accept("[AI] 调用 " + cfg.getProvider() + " model=" + cfg.getModel()
                + " 输入=\"" + text.trim() + "\"");
        if (cfg.isVerbose()) {
            log.accept("[AI] 请求体: " + requestBody);
        }

        String respText;
        int status;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.chatCompletionsUrl()))
                    .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds())) // S2-6 超时可控
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + cfg.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            status = resp.statusCode();
            respText = resp.body();
        } catch (java.net.http.HttpTimeoutException e) {
            throw new NlParseException("AI 请求超时（" + cfg.getTimeoutSeconds() + " 秒）", e);
        } catch (Exception e) {
            // 网络不通等：失败不静默。必须带上目标地址，否则 ConnectException 的 message 常为 null，
            // 只报 "ConnectException" 无法定位是哪个地址连不上。
            throw new NlParseException("AI 请求失败：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " - " + e.getMessage())
                    + "（目标: " + cfg.chatCompletionsUrl() + "）", e);
        }

        long elapsed = System.currentTimeMillis() - startedAt;
        if (cfg.isVerbose()) {
            log.accept("[AI] 返回(" + elapsed + "ms): " + respText);
        }

        // L1 HTTP 状态码非 2xx → 报错并带上响应体（否则排查只能靠猜）
        if (status / 100 != 2) {
            throw new NlParseException("AI 返回 HTTP " + status + "：" + brief(respText));
        }

        // L2 响应不是合法 JSON
        JsonObject root;
        try {
            root = JsonParser.parseString(respText).getAsJsonObject();
        } catch (Exception e) {
            throw new NlParseException("AI 响应不是合法 JSON：" + brief(respText), e);
        }

        // L3 choices[0].message.content 缺失
        String content = extractContent(root);
        if (content == null || content.trim().isEmpty()) {
            throw new NlParseException("AI 响应缺少 choices[0].message.content：" + brief(respText));
        }

        // L4 content 里不是纯 JSON → 容错提取
        String jsonText = extractJsonObject(content);
        if (jsonText == null) {
            throw new NlParseException("AI 返回内容中找不到 JSON 对象：" + brief(content));
        }

        JsonObject obj;
        try {
            obj = JsonParser.parseString(jsonText).getAsJsonObject();
        } catch (Exception e) {
            throw new NlParseException("AI 返回的 JSON 无法解析：" + brief(jsonText), e);
        }

        ParsedTask task = toParsedTask(obj);
        // rawJson 保留模型原话（含 JSON 原文），出问题时能直接看到
        task.setRawJson(jsonText);
        // 可观测：成败
        log.accept("[AI] 解析成功(" + elapsed + "ms): " + task);
        return task;
    }

    /** 组装 OpenAI 兼容请求体。 */
    private String buildRequestBody(String text) {
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", buildSystemPrompt());

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", text);

        com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
        messages.add(sys);
        messages.add(user);

        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.getModel());
        body.add("messages", messages);
        // 意图抽取要的是稳定可复现，不是创造性
        body.addProperty("temperature", 0);
        // 强制 JSON，减少返回客套话的概率（部分平台可能不支持，见容错 L4）
        JsonObject rf = new JsonObject();
        rf.addProperty("type", "json_object");
        body.add("response_format", rf);
        return body.toString();
    }

    /**
     * 构造 system prompt。
     * <p>
     * 必须带「当前时间 + 时区」：模型不知道「今天」是哪天，「下周三」就没法算 —— 这是最常见的错误来源。
     */
    String buildSystemPrompt() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        String nowText = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String zone = now.getZone().getId();
        String offset = now.getOffset().getId().replace("Z", "+00:00");

        return "你是一个任务解析器。把用户的一句话解析成 JSON 对象。\n"
                + "当前时间：" + nowText + "，时区：" + zone + "（" + offset + "）\n"
                + "今天是 " + now.format(DateTimeFormatter.ofPattern("EEEE", java.util.Locale.CHINA)) + "。\n"
                + "\n"
                + "只输出一个 JSON 对象，不要输出任何解释文字、不要用 markdown 代码块包裹。\n"
                + "字段与类型：\n"
                + "  title            string   任务名，必填，去掉时间状语后的核心动作\n"
                + "  due_at           string   截止/触发时刻，ISO8601 带时区，如 2026-10-01T18:00:00+08:00\n"
                + "  remind_at        string   提醒时刻，同上格式；用户没提就返回 null\n"
                + "  estimate_minutes integer  预估耗时（分钟），如\"大概4小时\"→240\n"
                + "  priority         integer  优先级，只能是 1 到 9 之间的整数（1 最低，9 最高），未提及返回 null\n"
                + "  note             string   补充说明，没有就返回 null\n"
                + "\n"
                + "规则：\n"
                + "1. 时间一律用上面给出的当前时间和时区推算，必须带时区偏移。\n"
                + "2. 无法判断的字段一律返回 null，绝对不要猜测或编造时间。\n"
                + "3. priority 只能是 1-9 的整数；不确定就返回 null，不要返回 0 或 100。\n"
                + "4. 用户没有明确说时间，due_at 就返回 null。";
    }

    /** L3：取 choices[0].message.content。 */
    private static String extractContent(JsonObject root) {
        try {
            JsonElement choices = root.get("choices");
            if (choices == null || !choices.isJsonArray() || choices.getAsJsonArray().isEmpty()) {
                return null;
            }
            JsonObject first = choices.getAsJsonArray().get(0).getAsJsonObject();
            JsonElement msg = first.get("message");
            if (msg == null || !msg.isJsonObject()) {
                return null;
            }
            JsonElement c = msg.getAsJsonObject().get("content");
            if (c == null || c.isJsonNull()) {
                return null;
            }
            return c.getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * L4：从可能带前后缀的文字里提取 JSON 对象（首个 {@code {} 到末个 {@code }}）。
     * <p>
     * 即使要求了纯 JSON，模型仍可能返回「好的，这是解析结果：{...}」。
     * 这是成本极低的保险。
     */
    static String extractJsonObject(String content) {
        if (content == null) {
            return null;
        }
        String s = content.trim();
        // 去掉 ```json ... ``` 包裹
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl >= 0) {
                s = s.substring(nl + 1);
            }
            int fence = s.lastIndexOf("```");
            if (fence >= 0) {
                s = s.substring(0, fence);
            }
            s = s.trim();
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) {
            return null;
        }
        return s.substring(start, end + 1);
    }

    /** 把 JSON 对象转成 {@link ParsedTask}（含 ISO8601 → epoch 毫秒的显式转换）。 */
    private ParsedTask toParsedTask(JsonObject obj) throws NlParseException {
        ParsedTask t = new ParsedTask();
        t.setTitle(str(obj, "title"));
        t.setNote(str(obj, "note"));
        t.setDueAt(toEpochMillis(obj, "due_at"));
        t.setRemindAt(toEpochMillis(obj, "remind_at"));
        t.setEstimateMinutes(integer(obj, "estimate_minutes"));
        t.setPriority(integer(obj, "priority"));
        return t;
    }

    private static String str(JsonObject o, String key) {
        try {
            JsonElement e = o.get(key);
            if (e == null || e.isJsonNull()) {
                return null;
            }
            String s = e.getAsString();
            return (s == null || s.trim().isEmpty()) ? null : s.trim();
        } catch (Exception ex) {
            return null;
        }
    }

    private static Integer integer(JsonObject o, String key) {
        try {
            JsonElement e = o.get(key);
            if (e == null || e.isJsonNull()) {
                return null;
            }
            if (e.getAsJsonPrimitive().isString()) {
                String s = e.getAsString().trim();
                if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
                    return null;
                }
                return Integer.valueOf(s);
            }
            return e.getAsInt();
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * ISO8601 字符串 → epoch 毫秒。
     * <p>
     * <b>时区陷阱</b>：模型可能返回不带时区的 {@code 2026-10-01T18:00:00}。
     * 此时<b>不要默认按 UTC 解析</b>（那会差 8 小时），而应显式按本地时区解释。
     */
    private Long toEpochMillis(JsonObject o, String key) throws NlParseException {
        String raw = str(o, key);
        if (raw == null) {
            return null;
        }
        // 纯数字：容忍模型直接给 epoch 毫秒
        if (raw.matches("-?\\d+")) {
            try {
                return Long.valueOf(raw);
            } catch (NumberFormatException ignore) {
                // 落到下面按 ISO8601 解析
            }
        }
        try {
            // 带偏移或 Z：直接解析
            return OffsetDateTime.parse(raw).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignore) {
            // 继续尝试其他形态
        }
        try {
            return Instant.parse(raw).toEpochMilli();
        } catch (DateTimeParseException ignore) {
            // 继续
        }
        try {
            // 不带时区 → 显式按本地时区解释（关键：不能当 UTC）
            LocalDateTime ldt = LocalDateTime.parse(raw);
            return ldt.atZone(clock.getZone()).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignore) {
            // 继续
        }
        try {
            // 最后尝试常见宽松格式：yyyy-MM-dd HH:mm[:ss]
            String normalized = raw.replace(' ', 'T');
            LocalDateTime ldt = LocalDateTime.parse(normalized);
            return ldt.atZone(clock.getZone()).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new NlParseException("无法解析时间字段 " + key + "：" + raw);
        }
    }

    private static String brief(String s) {
        if (s == null) {
            return "(null)";
        }
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= 300 ? one : one.substring(0, 300) + "…";
    }
}
