package h.Hchat;

import android.content.Context;
import android.content.pm.ApplicationInfo;

/**
 * 宿主环境快照：把 legacy `XC_LoadPackage.LoadPackageParam` 中的字段抽出来，
 * 使模块核心初始化不再依赖 legacy Xposed API（libxposed 102 环境不存在该类型）。
 */
public final class HostEnv {
    public final Context app;
    public final ClassLoader classLoader;
    public final String packageName;
    public final String processName;
    public final ApplicationInfo appInfo;

    public HostEnv(Context app, ClassLoader classLoader, String packageName,
                   String processName, ApplicationInfo appInfo) {
        this.app = app;
        this.classLoader = classLoader;
        this.packageName = packageName;
        this.processName = processName;
        this.appInfo = appInfo;
    }
}
