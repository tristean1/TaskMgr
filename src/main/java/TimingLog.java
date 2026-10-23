import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;

/** Timing key events only; independent of QQ's capped diagnostic stream. */
public final class TimingLog {
    private TimingLog() {}
    public static synchronized void write(String message) {
        Path dir = Path.of(System.getProperty("user.home"), ".taskmgr", "logs");
        try {
            write(dir, Clock.systemDefaultZone(), message);
        } catch (Exception e) {
            System.err.println("[计时日志失败] " + e.getMessage());
        }
    }

    static synchronized void write(Path dir, Clock clock, String message) throws java.io.IOException {
            LocalDate today = LocalDate.now(clock);
            Files.createDirectories(dir);
            Path file = dir.resolve("timing-events-" + today + ".log");
            byte[] bytes = (clock.instant() + " " + message.replace("\r", "").replace("\n", " | ") + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.lock()) {
                channel.position(channel.size());
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
            }
            try (var files = Files.newDirectoryStream(dir, "timing-events-????-??-??.log")) {
                for (Path old : files) {
                    String name = old.getFileName().toString();
                    LocalDate date;
                    try { date = LocalDate.parse(name.substring(14, 24)); }
                    catch (java.time.format.DateTimeParseException ignored) { continue; }
                    if (date.isBefore(today.minusMonths(1))) Files.deleteIfExists(old);
                }
            }
    }
}
