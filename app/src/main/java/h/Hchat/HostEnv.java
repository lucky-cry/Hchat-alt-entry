package h.Hchat;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import androidx.annotation.Nullable;

/**
 * 宿主环境快照：把 legacy `XC_LoadPackage.LoadPackageParam` 中的字段抽出来，
 * 使模块核心初始化不再依赖 legacy Xposed API（libxposed 102 环境不存在该类型）。
 *
 * <p>注意：libxposed 的 {@code onPackageReady} 发生在 Application 实例创建之前，
 * 此时 {@code ActivityThread.currentApplication()} 仍为 null，因此 {@link #app}
 * 允许为空。真正的 Application 由 {@code ModuleEntry} 挂 Application.onCreate
 * 后在回调里取得。</p>
 */
public final class HostEnv {
    /** 可能为 null：onPackageReady 阶段通常还拿不到 Application。 */
    @Nullable
    public final Context app;
    public final ClassLoader classLoader;
    public final String packageName;
    public final String processName;
    public final ApplicationInfo appInfo;

    public HostEnv(@Nullable Context app, ClassLoader classLoader, String packageName,
                   String processName, ApplicationInfo appInfo) {
        this.app = app;
        this.classLoader = classLoader;
        this.packageName = packageName;
        this.processName = processName;
        this.appInfo = appInfo;
    }
}
