import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * JsonChannel —— 一条"一行一个 JSON"的连接，**读写各归各的线程却互不抢锁**。
 *
 * <p><b>为什么需要这个类（2026-11-01 实测踩坑）</b>：
 * 用 {@code Channels.newInputStream(ch)} / {@code Channels.newOutputStream(ch)} 包同一个
 * {@link SocketChannel}，再由读线程阻塞读、写线程写，会**永久死锁**——
 * JDK 的 {@code Channels.writeFully} 会拿通道的 {@code blockingLock}，而阻塞读正握着它。
 * 现象是：客户端连上了、请求一个字节都没发出去，两遍都卡住不动（{@code jstack} 里
 * main 线程 BLOCKED 在 {@code Channels.writeFully}）。
 *
 * <p>解法：通道设成非阻塞，**一个线程 + Selector 同时负责读和写**，
 * 发送方只是往队列里塞行并 {@code wakeup()}。这样没有第二把锁可抢，也不会互相卡死。
 *
 * <p>每行按 UTF-8 解码；跨读边界的半个多字节字符留在缓冲区里，不会解码坏。
 */
public final class JsonChannel implements AutoCloseable {

    private final SocketChannel ch;
    private final Selector sel;
    private final SelectionKey key;
    private final ConcurrentLinkedQueue<String> outq = new ConcurrentLinkedQueue<>();
    private final ByteArrayOutputStream inbuf = new ByteArrayOutputStream(1024);
    private final Consumer<String> onLine;
    private final Runnable onClose;

    private volatile boolean closed;
    private ByteBuffer pending;
    private Thread thread;

    private JsonChannel(SocketChannel ch, Selector sel, SelectionKey key,
                        Consumer<String> onLine, Runnable onClose) {
        this.ch = ch;
        this.sel = sel;
        this.key = key;
        this.onLine = onLine;
        this.onClose = onClose;
    }

    public static JsonChannel open(SocketChannel ch, Consumer<String> onLine, Runnable onClose)
            throws IOException {
        Selector sel = Selector.open();
        ch.configureBlocking(false);
        SelectionKey key = ch.register(sel, SelectionKey.OP_READ);
        return new JsonChannel(ch, sel, key, onLine, onClose);
    }

    public void start(String threadName) {
        thread = new Thread(this::loop, threadName);
        thread.setDaemon(true);
        thread.start();
    }

    /** 线程安全：任意线程都可以塞一行。 */
    public void send(String line) {
        if (closed || line == null) return;
        outq.add(line);
        sel.wakeup();
    }

    public boolean isClosed() {
        return closed;
    }

    private void loop() {
        ByteBuffer buf = ByteBuffer.allocate(8192);
        try {
            while (!closed) {
                sel.select();
                if (closed) break;
                if (key.isReadable()) {
                    readAll(buf);
                }
                if (!closed && key.isWritable()) {
                    flushOut();
                }
                updateInterest();
            }
        } catch (Exception e) {
            // 对端断开是常态
        } finally {
            close();
        }
    }

    private void updateInterest() {
        if (closed || !key.isValid()) return;
        int ops = SelectionKey.OP_READ;
        if (!outq.isEmpty() || pending != null) ops |= SelectionKey.OP_WRITE;
        if (key.interestOps() != ops) key.interestOps(ops);
    }

    private void readAll(ByteBuffer buf) throws IOException {
        while (!closed) {
            buf.clear();
            int n = ch.read(buf);
            if (n < 0) {
                close();
                return;
            }
            if (n == 0) return;
            buf.flip();
            byte[] b = new byte[buf.remaining()];
            buf.get(b);
            inbuf.write(b, 0, b.length);
            drainLines();
        }
    }

    private void drainLines() {
        byte[] all = inbuf.toByteArray();
        int start = 0;
        for (int i = 0; i < all.length; i++) {
            if (all[i] != '\n') continue;
            int end = i;
            if (end > start && all[end - 1] == '\r') end--;
            String line = new String(all, start, end - start, StandardCharsets.UTF_8);
            start = i + 1;
            if (line.isBlank()) continue;
            try {
                onLine.accept(line);
            } catch (Throwable t) {
                // 单行处理失败不能拖垮连接
            }
        }
        inbuf.reset();
        if (start < all.length) inbuf.write(all, start, all.length - start);
    }

    private void flushOut() throws IOException {
        while (!closed) {
            if (pending == null) {
                String line = outq.poll();
                if (line == null) return;
                pending = ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
            while (pending.hasRemaining()) {
                int n = ch.write(pending);
                if (n == 0) return;      // 对端读得慢，等下次可写（pending 保住已写部分）
            }
            pending = null;
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            key.cancel();
        } catch (Throwable ignored) {
        }
        try {
            sel.close();
        } catch (Throwable ignored) {
        }
        try {
            ch.close();
        } catch (Throwable ignored) {
        }
        try {
            if (onClose != null) onClose.run();
        } catch (Throwable ignored) {
        }
    }
}
