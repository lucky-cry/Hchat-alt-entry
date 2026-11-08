package h.Hchat.utils;

/**
 * 模块日志入口。
 *
 * 迁移 libxposed 102 时，运行时不再有 XposedBridge，因此日志改为：
 * - 由入口注册 Sink（102 走 module.log，legacy 走 XposedBridge）；
 * - 未注册时回落 legacy 实现。
 */
public final class HLog {
    public interface Sink {
        void log(String message, Throwable throwable);
    }

    private static volatile Sink sink;

    private HLog() {
    }

    public static void installSink(Sink newSink) {
        sink = newSink;
    }

    public static void e(String message) {
        e(message, null);
    }

    public static void e(String message, Throwable throwable) {
        Sink current = sink;
        if (current != null) {
            try {
                current.log(message, throwable);
                return;
            } catch (Throwable ignored) {
                // 落到 legacy，避免日志本身把功能打断
            }
        }
        LogcatSink.log(message, throwable);
    }

    /** 兜底输出：Sink 未注入时写 logcat（102 环境不存在 legacy XposedBridge）。 */
    private static final class LogcatSink {
        static void log(String message, Throwable throwable) {
            android.util.Log.e("Hchat", message, throwable);
        }
    }
}
