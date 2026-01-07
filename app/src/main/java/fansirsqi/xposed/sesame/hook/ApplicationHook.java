package fansirsqi.xposed.sesame.hook;

import android.annotation.SuppressLint;
import android.app.Application;
import android.app.Service;
import android.content.*;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;


import androidx.core.content.ContextCompat;

import fansirsqi.xposed.sesame.hook.internal.SecurityBodyHelper;
import fansirsqi.xposed.sesame.hook.keepalive.SmartSchedulerManager;
import fansirsqi.xposed.sesame.hook.server.ModuleHttpServerManager;
import fansirsqi.xposed.sesame.hook.simple.SimplePageManager;
import fansirsqi.xposed.sesame.hook.internal.LocationHelper;
import fansirsqi.xposed.sesame.util.*;
import kotlin.Unit;
import lombok.Setter;

import org.luckypray.dexkit.DexKitBridge;

import java.io.File;
import java.util.Calendar;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Method;
import java.lang.reflect.Member;
import java.lang.reflect.InvocationTargetException;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import fansirsqi.xposed.sesame.BuildConfig;
import fansirsqi.xposed.sesame.data.Config;
import fansirsqi.xposed.sesame.data.General;
import fansirsqi.xposed.sesame.data.Status;
import fansirsqi.xposed.sesame.entity.AlipayVersion;
import fansirsqi.xposed.sesame.hook.rpc.bridge.NewRpcBridge;
import fansirsqi.xposed.sesame.hook.rpc.bridge.OldRpcBridge;
import fansirsqi.xposed.sesame.hook.rpc.bridge.RpcBridge;
import fansirsqi.xposed.sesame.hook.rpc.bridge.RpcVersion;
import fansirsqi.xposed.sesame.hook.rpc.intervallimit.RpcIntervalLimit;
import fansirsqi.xposed.sesame.hook.server.ModuleHttpServer;
import fansirsqi.xposed.sesame.model.BaseModel;
import fansirsqi.xposed.sesame.model.Model;
import fansirsqi.xposed.sesame.newutil.DataStore;
import fansirsqi.xposed.sesame.task.MainTask;
import fansirsqi.xposed.sesame.task.ModelTask;
import fansirsqi.xposed.sesame.task.TaskRunnerAdapter;
import fansirsqi.xposed.sesame.util.maps.UserMap;
import fansirsqi.xposed.sesame.hook.rpc.debug.DebugRpc;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;
import kotlin.jvm.JvmStatic;
import lombok.Getter;

public class ApplicationHook {
    static final String TAG = ApplicationHook.class.getSimpleName();
    public XposedInterface xposedInterface = null;
    @Getter
    @Setter
    private ModuleHttpServer httpServer;

    static String finalProcessName = "";

    /**
     * 广播动作常量
     * 删除了 EXECUTE, PRE_WAKEUP，因为现在使用内部调度器
     */
    private static class BroadcastActions {
        static final String RESTART = "com.eg.android.AlipayGphone.sesame.restart";
        // static final String EXECUTE = "com.eg.android.AlipayGphone.sesame.execute"; // 已废弃
        // static final String PRE_WAKEUP = "com.eg.android.AlipayGphone.sesame.prewakeup"; // 已废弃
        static final String RE_LOGIN = "com.eg.android.AlipayGphone.sesame.reLogin";
        static final String STATUS = "com.eg.android.AlipayGphone.sesame.status";
        static final String RPC_TEST = "com.eg.android.AlipayGphone.sesame.rpctest";
        static final String STOP = "com.eg.android.AlipayGphone.sesame.stop";
    }

    private static class AlipayClasses {
        static final String APPLICATION = "com.alipay.mobile.framework.AlipayApplication";
        static final String SOCIAL_SDK = "com.alipay.mobile.personalbase.service.SocialSdkContactService";
    }

    private static class ReflectionCache {
        private static Class<?> alipayApplicationClass;
        private static Class<?> socialSdkContactServiceClass;
        private static volatile boolean initialized = false;

        static void initialize(ClassLoader loader) {
            if (initialized) return;
            try {
                alipayApplicationClass = XposedHelpers.findClass(AlipayClasses.APPLICATION, loader);
                socialSdkContactServiceClass = XposedHelpers.findClass(AlipayClasses.SOCIAL_SDK, loader);
                initialized = true;
            } catch (Throwable t) {
                // Ignore
            }
        }

        static Class<?> getAlipayApplicationClass(ClassLoader loader) {
            if (!initialized) initialize(loader);
            try {
                if (alipayApplicationClass != null) return alipayApplicationClass;
                return XposedHelpers.findClass(AlipayClasses.APPLICATION, loader);
            } catch (Throwable t) {
                return null;
            }
        }

        static Class<?> getSocialSdkClass(ClassLoader loader) {
            if (!initialized) initialize(loader);
            try {
                if (socialSdkContactServiceClass != null) return socialSdkContactServiceClass;
                return XposedHelpers.findClass(AlipayClasses.SOCIAL_SDK, loader);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    @Getter
    private static ClassLoader classLoader = null;
    private static Object microApplicationContextObject = null;

    @SuppressLint("StaticFieldLeak")
    static volatile Context appContext = null;


    @JvmStatic
    public static Context getAppContext() {
        return appContext;
    }

    /**
     * 确保智能调度器已初始化
     */
    private static void ensureScheduler() {
        // SmartSchedulerManager 内部已有防重复初始化判断，直接调用即可
        if (appContext != null) {
            SmartSchedulerManager.INSTANCE.initialize(appContext);
        }
    }

    /**
     * 任务锁管理器 (AutoCloseable)
     */
    private static class TaskLock implements AutoCloseable {
        private final boolean acquired;

        TaskLock() {
            synchronized (taskLock) {
                if (isTaskRunning) {
                    acquired = false;
                    throw new IllegalStateException("任务已在运行中");
                }
                isTaskRunning = true;
                acquired = true;
            }
        }

        @Override
        public void close() {
            if (acquired) {
                synchronized (taskLock) {
                    isTaskRunning = false;
                }
            }
        }
    }

    @Getter
    static AlipayVersion alipayVersion = new AlipayVersion("");
    private static volatile boolean hooked = false;

    @JvmStatic
    public static boolean isHooked() {
        return hooked;
    }

    private static volatile boolean init = false;
    static volatile Calendar dayCalendar;
    @Getter
    static volatile boolean offline = false;
    @Getter
    static final AtomicInteger reLoginCount = new AtomicInteger(0);
    private static volatile boolean batteryPermissionChecked = false;

    @SuppressLint("StaticFieldLeak")
    static Service service;
    @Getter
    static Handler mainHandler;
    @Getter
    static MainTask mainTask;

    static volatile RpcBridge rpcBridge;
    private static final Object rpcBridgeLock = new Object();
    @Getter
    private static RpcVersion rpcVersion;

    private static volatile boolean isTaskRunning = false;
    private static final Object taskLock = new Object();

    public static void setOffline(boolean offline) {
        ApplicationHook.offline = offline;
    }

    private static volatile long lastExecTime = 0;
    public static volatile long nextExecutionTime = 0;
    private static final long MAX_INACTIVE_TIME = 3600000;

    static {
        dayCalendar = Calendar.getInstance();
        resetToMidnight(dayCalendar);
        Method m = null;
        try {
            //noinspection JavaReflectionMemberAccess
            m = XposedBridge.class.getDeclaredMethod("deoptimizeMethod", Member.class);
        } catch (Throwable t) {
            XposedBridge.log("E/" + TAG + " " + android.util.Log.getStackTraceString(t));
        }
        deoptimizeMethod = m;
    }

    private final static Method deoptimizeMethod;

    static void deoptimizeMethod(Class<?> c) throws InvocationTargetException, IllegalAccessException {
        for (Method m : c.getDeclaredMethods()) {
            if (deoptimizeMethod != null && m.getName().equals("makeApplicationInner")) {
                deoptimizeMethod.invoke(null, m);
            }
        }
    }

    @JvmStatic
    public static void scheduleNextExecution() {
        scheduleNextExecutionInternal(lastExecTime);
    }

    /**
     * 调度定时执行 (重构：使用 SmartSchedulerManager)
     */
    private static void scheduleNextExecutionInternal(long lastExecTime) {
        try {
            checkInactiveTime();
            int checkInterval = BaseModel.Companion.getCheckInterval().getValue();
            List<String> execAtTimeList = BaseModel.Companion.getExecAtTimeList().getValue();
            if (execAtTimeList != null && execAtTimeList.contains("-1")) {
                Log.record(TAG, "定时执行未开启");
                return;
            }

            long delayMillis = checkInterval;
            long targetTime = 0;

            try {
                if (execAtTimeList != null) {
                    Calendar lastExecTimeCalendar = TimeUtil.getCalendarByTimeMillis(lastExecTime);
                    Calendar nextExecTimeCalendar = TimeUtil.getCalendarByTimeMillis(lastExecTime + checkInterval);
                    for (String execAtTime : execAtTimeList) {
                        Calendar execAtTimeCalendar = TimeUtil.getTodayCalendarByTimeStr(execAtTime);
                        if (execAtTimeCalendar != null && lastExecTimeCalendar.compareTo(execAtTimeCalendar) < 0 && nextExecTimeCalendar.compareTo(execAtTimeCalendar) > 0) {
                            Log.record(TAG, "设置定时执行:" + execAtTime);
                            targetTime = execAtTimeCalendar.getTimeInMillis();
                            delayMillis = targetTime - lastExecTime;
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                Log.printStackTrace(TAG, "execAtTime err:：", e);
            }

            nextExecutionTime = targetTime > 0 ? targetTime : (lastExecTime + delayMillis);

            ensureScheduler();

            // 这里的 Lambda 会在 delayMillis 毫秒后被执行，期间持有 WakeLock
            SmartSchedulerManager.INSTANCE.schedule(delayMillis, "轮询任务", () -> {
                // 触发执行逻辑
                execHandler();
                return Unit.INSTANCE;
            });

        } catch (Exception e) {
            Log.printStackTrace(TAG, "scheduleNextExecution：", e);
        }
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private void loadNativeLibs(Context context, File soFile) {
        try {
            File finalSoFile = AssetUtil.INSTANCE.copyStorageSoFileToPrivateDir(context, soFile);
            if (finalSoFile != null) {
                System.load(finalSoFile.getAbsolutePath());
                Log.record(TAG, "Loading " + soFile.getName() + " from :" + finalSoFile.getAbsolutePath(), 0);
            } else {
                Detector.INSTANCE.loadLibrary(soFile.getName().replace(".so", "").replace("lib", ""));
            }
        } catch (Exception e) {
            Log.printStackTrace(TAG, "载入so库失败！！", e);
        }
    }

    public void loadPackage(XposedModuleInterface.PackageLoadedParam lpparam) {
        if (!General.PACKAGE_NAME.equals(lpparam.getPackageName())) return;
        classLoader = lpparam.getClassLoader();
        handleHookLogic(classLoader, lpparam.getPackageName(), lpparam.getApplicationInfo().sourceDir, lpparam);
    }

    public void loadPackageCompat(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!General.PACKAGE_NAME.equals(lpparam.packageName)) return;
        classLoader = lpparam.classLoader;
        String apkPath = lpparam.appInfo != null ? lpparam.appInfo.sourceDir : null;
        handleHookLogic(classLoader, lpparam.packageName, apkPath, lpparam);
    }


    @SuppressLint("PrivateApi")
    private void handleHookLogic(ClassLoader classLoader, String packageName, String apkPath, Object rawParam) {
        // 1. 获取进程名
        String processName = null;
        if (rawParam instanceof XC_LoadPackage.LoadPackageParam) {
            processName = ((XC_LoadPackage.LoadPackageParam) rawParam).processName;
        } else if (rawParam instanceof XposedModuleInterface.PackageLoadedParam) {
            processName = XposedEnv.INSTANCE.getProcessName();
        }
        finalProcessName = processName;

        // 2. 【关键修复】进程过滤
        // 判断是否为主进程
        boolean isMainProcess = General.PACKAGE_NAME.equals(processName);
        // 判断是否为小组件进程 (如果你有小组件功能)
        boolean isWidgetProcess = processName != null && processName.endsWith(":widgetProvider");
        // 如果既不是主进程，也不是小组件进程，直接退出，不执行后续任何逻辑
        if (!isMainProcess && !isWidgetProcess) {
            // 可以留一行日志方便调试，证明其他进程被过滤了
            Log.record(TAG, "跳过辅助进程: " + processName);
            return;
        }


        DataStore.INSTANCE.init(Files.CONFIG_DIR);
        if (hooked) return;
        hooked = true;


        VersionHook.installHook(classLoader);
        ReflectionCache.initialize(classLoader);

        try {
            CaptchaHook.INSTANCE.setupHook(classLoader);
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "验证码Hook初始化失败", t);
        }

        try {
            try {
                Class<?> loadedApkClass = classLoader.loadClass("android.app.LoadedApk");
                deoptimizeMethod(loadedApkClass);
            } catch (Throwable t) {
                // ignore
            }
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    mainHandler = new Handler(Looper.getMainLooper());
                    appContext = (Context) param.args[0];
                    Log.init(appContext);

                    // 初始化调度器
                    ensureScheduler();

                    if (General.PACKAGE_NAME.equals(finalProcessName) || (finalProcessName != null && finalProcessName.endsWith(":widgetProvider"))) {
                        registerBroadcastReceiver(appContext);
                    }
                    SecurityBodyHelper.INSTANCE.init(classLoader);
                    LocationHelper.INSTANCE.init(classLoader);

                    if (VersionHook.hasVersion()) {
                        alipayVersion = VersionHook.getCapturedVersion();
                        Log.record(TAG, "📦 支付宝版本(Hook): " + alipayVersion.getVersionString(), 0);
                    } else {
                        try {
                            PackageInfo pInfo = appContext.getPackageManager().getPackageInfo(packageName, 0);
                            alipayVersion = new AlipayVersion(Objects.requireNonNullElse(pInfo.versionName, ""));
                        } catch (Exception e) {
                            alipayVersion = new AlipayVersion("");
                        }
                    }

                    loadNativeLibs(appContext, AssetUtil.INSTANCE.getCheckerDestFile());
                    loadNativeLibs(appContext, AssetUtil.INSTANCE.getDexkitDestFile());

                    if (VersionHook.hasVersion() && "10.7.26.8100".equals(alipayVersion.getVersionString())) {
                        HookUtil.INSTANCE.fuckAccounLimit(classLoader);
                    }

                    if (VersionHook.hasVersion() && alipayVersion.getVersionString() != null) {
                        String version = alipayVersion.getVersionString();
                        if (version.matches("^([0-9]\\.[0-9]+\\.[0-9]+\\.[0-9]+|10\\.[0-5]\\.[0-9]+\\.[0-9]+|10\\.6\\.([0-9]|[0-4][0-9]|5[0-8])\\.[0-9]+)$")) {
                            SimplePageManager.INSTANCE.enableWindowMonitoring(classLoader);
                            SimplePageManager.INSTANCE.addHandler(
                                    "com.alipay.mobile.nebulax.xriver.activity.XRiverActivity",
                                    new Captcha1Handler());
                            SimplePageManager.INSTANCE.addHandler(
                                    "com.eg.android.AlipayGphone.AlipayLogin",
                                    new Captcha2Handler());
                        }
                    }

                    if (BuildConfig.DEBUG) {
                        try {
                            ModuleHttpServerManager.INSTANCE.startIfNeeded(
                                    8080,
                                    "ET3vB^#td87sQqKaY*eMUJXP",
                                    XposedEnv.processName,
                                    General.PACKAGE_NAME
                            );
                        } catch (Throwable e) {
                            // ignore
                        }
                    }
                    super.afterHookedMethod(param);
                }
            });
        } catch (Exception e) {
            Log.printStackTrace(e);
        }

        try {
            XposedHelpers.findAndHookMethod("com.alipay.mobile.quinox.LauncherActivity", classLoader, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String targetUid = HookUtil.INSTANCE.getUserId(classLoader);
                            if (targetUid == null) {
                                Toast.INSTANCE.show("用户未登录");
                                return;
                            }
                            if (!init) {
                                if (initHandler()) {
                                    init = true;
                                }
                                return;
                            }
                            String currentUid = UserMap.INSTANCE.getCurrentUid();
                            if (!targetUid.equals(currentUid)) {
                                if (currentUid != null) {
                                    initHandler();
                                    lastExecTime = 0;
                                    Toast.INSTANCE.show("用户已切换");
                                    return;
                                }
                                HookUtil.INSTANCE.hookUser(classLoader);
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.printStackTrace(TAG, "hook login err", t);
        }
        try {
            XposedHelpers.findAndHookMethod("android.app.Service", classLoader, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Service appService = (Service) param.thisObject;
                            if (!General.CURRENT_USING_SERVICE.equals(appService.getClass().getCanonicalName())) {
                                return;
                            }
                            appContext = appService.getApplicationContext();
                            // 再次确保调度器初始化，因为Service Context通常更好
                            ensureScheduler();

                            boolean isok = Detector.INSTANCE.isLegitimateEnvironment(appContext);
                            if (isok) {
                                Detector.INSTANCE.dangerous(appContext);
                                return;
                            }
                            try (DexKitBridge ignored = DexKitBridge.create(apkPath)) {
                                Log.record(TAG, "hook dexkit successfully", 0);
                            }
                            service = appService;
                            mainTask = MainTask.newInstance("MAIN_TASK", () -> {
                                try (TaskLock ignored = new TaskLock()) {
                                    if (!init) return;
                                    if (!Config.isLoaded()) return;

                                    long currentTime = System.currentTimeMillis();
                                    if (currentTime - lastExecTime < 5000) return;
                                    final long MIN_EXEC_INTERVAL = 2000;
                                    long timeSinceLastExec = currentTime - lastExecTime;

                                    // 如果通过调度器回调触发，这里时间应该是准确的
                                    if (timeSinceLastExec < MIN_EXEC_INTERVAL) {
                                        Log.record(TAG, "⚠️ 间隔过短(" + timeSinceLastExec + "ms)，跳过");
                                        // 重新调度
                                        SmartSchedulerManager.INSTANCE.schedule(BaseModel.Companion.getCheckInterval().getValue(), "间隔重试", () -> {
                                            execHandler();
                                            return Unit.INSTANCE;
                                        });
                                        return;
                                    }

                                    String currentUid = UserMap.INSTANCE.getCurrentUid();
                                    String targetUid = HookUtil.INSTANCE.getUserId(classLoader);
                                    if (targetUid == null || !targetUid.equals(currentUid)) {
                                        reOpenApp();
                                        return;
                                    }
                                    lastExecTime = currentTime;
                                    TaskRunnerAdapter adapter = new TaskRunnerAdapter();
                                    adapter.run();
                                    // 任务跑完，调度下一次
                                    scheduleNextExecutionInternal(lastExecTime);
                                } catch (IllegalStateException e) {
                                    Log.record(TAG, "⚠️ " + e.getMessage());
                                } catch (Exception e) {
                                    Log.printStackTrace(TAG, e);
                                }
                            });
                            dayCalendar = Calendar.getInstance();
                            if (initHandler()) {
                                init = true;
                            }
                        }
                    }

            );
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }

        try {
            XposedHelpers.findAndHookMethod("android.app.Service", classLoader, "onDestroy",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Service service = (Service) param.thisObject;
                            if (!General.CURRENT_USING_SERVICE.equals(service.getClass().getCanonicalName()))
                                return;
                            Notify.updateStatusText("支付宝前台服务被销毁");
                            destroyHandler();
                            try {
                                ModuleHttpServerManager.INSTANCE.stopIfRunning();
                            } catch (Throwable ignore) {
                            }
                            restartByBroadcast();
                        }
                    });
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }

        HookUtil.INSTANCE.hookOtherService(classLoader);
        hooked = true;
    }

    /**
     * 设置定时唤醒 (重构：使用 SmartSchedulerManager)
     * 注意：由于不再使用 AlarmManager，此功能依赖于进程存活。
     */
    private static void setWakenAtTimeAlarm() {
        if (appContext == null) return;
        ensureScheduler();

        List<String> wakenAtTimeList = BaseModel.Companion.getWakenAtTimeList().getValue();
        if (wakenAtTimeList != null && wakenAtTimeList.contains("-1")) {
            return;
        }


        // 2. 调度 0点 任务
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_MONTH, 1);
        resetToMidnight(calendar);
        long delayToMidnight = calendar.getTimeInMillis() - System.currentTimeMillis();

        if (delayToMidnight > 0) {
            SmartSchedulerManager.INSTANCE.schedule(delayToMidnight, "每日0点任务", () -> {
                Log.record(TAG, "⏰ 0点任务触发");
                execHandler();
                setWakenAtTimeAlarm(); // 递归设置明天的
                return Unit.INSTANCE;
            });
            Log.record(TAG, "⏰ 已计划明日0点任务");
        }

        // 3. 调度自定义时间
        if (wakenAtTimeList != null && !wakenAtTimeList.isEmpty()) {
            Calendar nowCalendar = Calendar.getInstance();
            for (String wakenAtTime : wakenAtTimeList) {
                try {
                    Calendar targetTime = TimeUtil.getTodayCalendarByTimeStr(wakenAtTime);
                    if (targetTime != null && targetTime.compareTo(nowCalendar) > 0) {
                        long delay = targetTime.getTimeInMillis() - System.currentTimeMillis();
                        SmartSchedulerManager.INSTANCE.schedule(delay, "自定义: " + wakenAtTime, () -> {
                            Log.record(TAG, "⏰ 自定义时间触发: " + wakenAtTime);
                            execHandler();
                            return Unit.INSTANCE;
                        });
                        Log.record(TAG, "⏰ 已计划任务: " + wakenAtTime);
                    }
                } catch (Exception e) {
                    Log.printStackTrace(TAG, "设置自定义时间失败", e);
                }
            }
        }
    }

    private static synchronized Boolean initHandler() {
        try {
            if (init) destroyHandler();

            ensureScheduler();
            Model.initAllModel();
            if (service == null) return false;

            String userId = HookUtil.INSTANCE.getUserId(classLoader);
            if (userId == null) {
                Toast.INSTANCE.show("用户未登录");
                return false;
            }

            HookUtil.INSTANCE.hookUser(classLoader);
            Log.record(TAG, "芝麻粒-TK 开始初始化 (Kotlin Coroutines版)...");

            Config.load(userId);
            if (!Config.isLoaded()) return false;

            Notify.start(service);
            setWakenAtTimeAlarm();

            synchronized (rpcBridgeLock) {
                if (BaseModel.Companion.getNewRpc().getValue()) {
                    rpcBridge = new NewRpcBridge();
                } else {
                    rpcBridge = new OldRpcBridge();
                }
                rpcBridge.load();
                rpcVersion = rpcBridge.getVersion();
            }

            if (BaseModel.Companion.getNewRpc().getValue() && BaseModel.Companion.getDebugMode().getValue()) {
                HookUtil.INSTANCE.hookRpcBridgeExtension(classLoader, BaseModel.Companion.getSendHookData().getValue(), BaseModel.Companion.getSendHookDataUrl().getValue());
                HookUtil.INSTANCE.hookDefaultBridgeCallback(classLoader);
            }

            TokenHooker.INSTANCE.start(userId);
            // 检查配置开关
            if (BaseModel.Companion.getBatteryPerm().getValue()) {
                // 如果还没检查过权限 (利用 static 变量防止多次重复弹窗)
                if (!batteryPermissionChecked) {
                    boolean hasPermission = PermissionUtil.checkBatteryPermissions(appContext);
                    if (hasPermission) {
                        batteryPermissionChecked = true;
                    } else {
                        Log.record(TAG, "无后台运行权限，2秒后申请");
                        batteryPermissionChecked = true;
                        mainHandler.postDelayed(() -> {
                            if (!PermissionUtil.checkOrRequestBatteryPermissions(appContext)) {
                                Toast.INSTANCE.show("请授予支付宝始终在后台运行权限");
                            }
                        }, 2000);
                    }
                }
            }

            Model.bootAllModel(classLoader);
            Status.load(userId);
            updateDay();
            String successMsg = "芝麻粒-TK 加载成功✨";
            Log.record(successMsg);
            Toast.INSTANCE.show(successMsg);
            offline = false;
            init = true;
            execHandler();
            return true;
        } catch (Throwable th) {
            Log.printStackTrace(TAG, "startHandler", th);
            return false;
        }
    }

    static synchronized void destroyHandler() {
        try {
            GlobalThreadPools.INSTANCE.shutdownAndRestart();
            if (service != null) {
                stopHandler();
                BaseModel.Companion.destroyData();
                Status.unload();
                Notify.stop();
                RpcIntervalLimit.INSTANCE.clearIntervalLimit();
                Config.unload();
                UserMap.unload();
            }
            // 关键：清理协程调度器
            SmartSchedulerManager.INSTANCE.cleanup();

            synchronized (rpcBridgeLock) {
                if (rpcBridge != null) {
                    rpcVersion = null;
                    rpcBridge.unload();
                    rpcBridge = null;
                }
                ModelTask.stopAllTask();
            }
        } catch (Throwable th) {
            Log.printStackTrace(TAG, "stopHandler err:", th);
        }
    }

    static void execHandler() {
        mainTask.startTask(false);
    }

    private static void checkInactiveTime() {
        if (lastExecTime == 0) return;
        long currentTime = System.currentTimeMillis();
        long inactiveTime = currentTime - lastExecTime;
        if (inactiveTime > MAX_INACTIVE_TIME) {
            Log.record(TAG, "⚠️ 检测到长时间未执行，重新登录");
            reOpenApp();
        }
    }

    private static void stopHandler() {
        mainTask.stopTask();
        ModelTask.stopAllTask();
    }

    public static void updateDay() {
        Calendar nowCalendar = Calendar.getInstance();
        if (dayCalendar == null) {
            dayCalendar = (Calendar) nowCalendar.clone();
            resetToMidnight(dayCalendar);
            setWakenAtTimeAlarm();
            return;
        }
        int nowDay = nowCalendar.get(Calendar.DAY_OF_MONTH);
        if (dayCalendar.get(Calendar.DAY_OF_MONTH) != nowDay) {
            dayCalendar = (Calendar) nowCalendar.clone();
            resetToMidnight(dayCalendar);
            Log.record(TAG, "日期更新");
            setWakenAtTimeAlarm();
        }
        try {
            Status.save(nowCalendar);
        } catch (Exception e) {
            // ignore
        }
    }

    public static void sendBroadcast(String action) {
        try {
            appContext.sendBroadcast(new Intent(action));
        } catch (Throwable th) {
            Log.printStackTrace(TAG, th);
        }
    }

    public static void sendBroadcastShell(String API, String message) {
        Intent intent = new Intent("fansirsqi.xposed.sesame.SHELL");
        intent.putExtra(API, message);
        appContext.sendBroadcast(intent, null);
    }

    public static void reLoginByBroadcast() {
        sendBroadcast(BroadcastActions.RE_LOGIN);
    }

    public static void restartByBroadcast() {
        sendBroadcast(BroadcastActions.RESTART);
    }

    private static void resetToMidnight(Calendar calendar) {
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
    }


    public static void reOpenApp() {
        // 重构：使用 SmartSchedulerManager 替代 Handler/Alarm
        long delayMillis = 20_000L;
        ensureScheduler();
        SmartSchedulerManager.INSTANCE.schedule(delayMillis, "重新登录", () -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setClassName(General.PACKAGE_NAME, General.CURRENT_USING_ACTIVITY);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                offline = true;
                if (appContext != null) {
                    appContext.startActivity(intent);
                }
            } catch (Exception e) {
                Log.error(TAG, "重启Activity失败: " + e.getMessage());
            }
            return Unit.INSTANCE;
        });
    }

    static class AlipayBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                // 优化建议：显式过滤
                if (finalProcessName != null && finalProcessName.endsWith(":widgetProvider")) {
                    Log.record(TAG, "小组件进程收到广播，保持活跃");
                    return;
                }
                String action = intent.getAction();
                if (action != null) {
                    switch (action) {
                        case BroadcastActions.RESTART:
                            GlobalThreadPools.INSTANCE.execute(ApplicationHook::initHandler);
                            break;
                        case BroadcastActions.RE_LOGIN:
                            reOpenApp();
                            break;
                        case BroadcastActions.RPC_TEST:
                            GlobalThreadPools.INSTANCE.execute(() -> {
                                try {
                                    String method = intent.getStringExtra("method");
                                    String data = intent.getStringExtra("data");
                                    String type = intent.getStringExtra("type");
                                    DebugRpc rpcInstance = new DebugRpc();
                                    rpcInstance.start(method, data, type);
                                } catch (Throwable th) {
                                    // ignore
                                }
                            });
                            break;

                        case BroadcastActions.STOP:
                            Log.record(TAG, "🛑 收到手动停止指令");
                            GlobalThreadPools.INSTANCE.execute(ApplicationHook::stopHandler);
                            Toast.INSTANCE.show("🛑 任务已尝试停止");
                            break;
                    }
                }
            } catch (Throwable t) {
                // ignore
            }
        }
    }

    void registerBroadcastReceiver(Context context) {
        try {
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(BroadcastActions.RESTART);
            intentFilter.addAction(BroadcastActions.RE_LOGIN);
            intentFilter.addAction(BroadcastActions.STATUS);
            intentFilter.addAction(BroadcastActions.RPC_TEST);
            intentFilter.addAction(BroadcastActions.STOP);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(new AlipayBroadcastReceiver(), intentFilter, Context.RECEIVER_EXPORTED);
            } else {
                ContextCompat.registerReceiver(context, new AlipayBroadcastReceiver(), intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
            }
        } catch (Throwable th) {
            Log.record(TAG, "hook registerBroadcastReceiver err:");
        }
    }
}