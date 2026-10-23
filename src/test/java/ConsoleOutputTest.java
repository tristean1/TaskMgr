import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Run in a real terminal through start_console.ps1 with
 * --add-opens=java.base/java.io=ALL-UNNAMED (test JVM only).
 * Inspect the actual PrintStream encoders: file.encoding is not sufficient.
 */
public class ConsoleOutputTest {
    public static void main(String[] args) throws Exception {
        var field = PrintStream.class.getDeclaredField("charOut");
        field.setAccessible(true);
        Charset stdout = Charset.forName(((OutputStreamWriter) field.get(System.out)).getEncoding());
        Charset stderr = Charset.forName(((OutputStreamWriter) field.get(System.err)).getEncoding());
        String report = "stdout=" + stdout + "; stderr=" + stderr
                + "; console=" + (System.console() == null ? "redirected" : System.console().charset()) + "\n";
        Files.writeString(Path.of(args[0]), report, StandardCharsets.UTF_8);
        if (!stdout.equals(StandardCharsets.UTF_8) || !stderr.equals(StandardCharsets.UTF_8)) {
            throw new AssertionError("Output encoders must both be UTF-8; " + report);
        }
        System.out.println("标准输出：中文任务已完成");
        System.err.println("错误输出：未找到中文任务");
    }
}
