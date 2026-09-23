import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * IcsExporter —— 把「未来 7 天、未完成未删除」的任务导出成 iCalendar(.ics) 文件。
 * 这是「程序没开也不漏提醒」的手机兜底通道：导一次 → 导入手机日历 → 手机准点提醒。
 * 不依赖 UI；时间一律按 UTC 输出（避免时区偏移 8 小时）；UID 使用任务 UUID 保证可重复导入不产生重复条目。
 */
public class IcsExporter {

    /** 默认导出文件名（相对当前工作目录）。 */
    public static final String DEFAULT_FILE = "tasks.ics";

    private final Connection conn;
    private final Clock clock;

    /** 未来窗口天数（M3 A4：只导未来 7 天）。 */
    private static final int WINDOW_DAYS = 7;

    private static final DateTimeFormatter UTC_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    public IcsExporter(Connection conn) {
        this(conn, Clock.systemDefaultZone());
    }

    /** 允许注入 {@link Clock} 以便测试固定“现在”。 */
    public IcsExporter(Connection conn, Clock clock) {
        this.conn = conn;
        this.clock = clock;
    }

    /** 导出到默认文件，返回事件条数。 */
    public int export() throws SQLException, IOException {
        return export(DEFAULT_FILE);
    }

    /** 导出到指定路径，返回事件条数。 */
    public int export(String filename) throws SQLException, IOException {
        Instant now = clock.instant();
        Instant horizon = now.plusSeconds(WINDOW_DAYS * 86400L);
        String ics = build(now, horizon);
        Files.write(Paths.get(filename), ics.getBytes(StandardCharsets.UTF_8));
        return countEvents(ics);
    }

    /** 生成 iCalendar 正文。 */
    public String build(Instant now, Instant horizon) throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\n");
        sb.append("VERSION:2.0\r\n");
        sb.append("PRODID:-//TaskMgr//CN\r\n");
        sb.append("CALSCALE:GREGORIAN\r\n");
        sb.append("X-WR-CALNAME:TaskMgr 任务提醒\r\n");

        long from = now.toEpochMilli();
        long to = horizon.toEpochMilli();
        int n = 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id,title,note,trigger_at FROM tasks "
                + "WHERE status NOT IN ('done','removed') "
                + "  AND trigger_at IS NOT NULL "
                + "  AND trigger_at >= ? AND trigger_at <= ? "
                + "ORDER BY trigger_at ASC")) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    String title = rs.getString("title");
                    String note = rs.getString("note");
                    long triggerAt = rs.getLong("trigger_at");
                    appendEvent(sb, id, title, note, triggerAt, now);
                    n++;
                }
            }
        }
        sb.append("END:VCALENDAR\r\n");
        return sb.toString();
    }

    private void appendEvent(StringBuilder sb, String id, String title, String note,
                             long triggerAt, Instant now) {
        String uid = sanitize(id) + "@taskmgr";
        String dtstart = UTC_FMT.format(Instant.ofEpochMilli(triggerAt));
        String dtstamp = UTC_FMT.format(now);
        line(sb, "BEGIN:VEVENT");
        line(sb, "UID:" + uid);
        line(sb, "DTSTAMP:" + dtstamp);
        line(sb, "DTSTART:" + dtstart);
        line(sb, "SUMMARY:" + escape(title));
        if (note != null && !note.isEmpty()) {
            line(sb, "DESCRIPTION:" + escape(note));
        }
        line(sb, "BEGIN:VALARM");
        line(sb, "ACTION:DISPLAY");
        line(sb, "TRIGGER:PT0M");
        line(sb, "DESCRIPTION:" + escape(title));
        line(sb, "END:VALARM");
        line(sb, "END:VEVENT");
    }

    /** 写一行并用 CRLF 收尾；超长内容按 “≤70 字符、UTF-8 字节 ≤74” 折行（不拆开多字节字符）。 */
    private void line(StringBuilder sb, String s) {
        // 简化：按字符累积，达到 60 个字符就折（中文 3 字节 < 60*3=180，但 60 个 ASCII 也稳定 <74）
        int col = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int charLen = Character.charCount(cp);
            int utf8Len = utf8Length(cp);
            if (col + utf8Len > 60 && col > 0) {
                sb.append("\r\n ");
                col = 1;
            }
            sb.appendCodePoint(cp);
            col += utf8Len;
            i += charLen;
        }
        sb.append("\r\n");
    }
/** iCalendar 文本转义：反斜杠、分号、逗号、换行。 */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n");
    }

    /** 计算单个 code point 的 UTF-8 字节数。 */
    private static int utf8Length(int cp) {
        if (cp < 0x80) return 1;
        if (cp < 0x800) return 2;
        if (cp < 0x10000) return 3;
        return 4;
    }

    /** 生成安全内容：移除可能导致不合法的空白与换行。 */
    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\r\\n\\t ]+", "_");
    }

    /** 统计事件条数。 */
    private static int countEvents(String ics) {
        int n = 0, idx = 0;
        String key = "BEGIN:VEVENT";
        while ((idx = ics.indexOf(key, idx)) >= 0) {
            n++;
            idx += key.length();
        }
        return n;
    }
}