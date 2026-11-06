package com.eta.afb;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.error.HookFailedError;

import java.io.File;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 反诈上报阻断（AntiFraudBlock / libxposed API 102 版）
 *
 * 作用域：com.coloros.phonemanager（含 :remote / :scan / :card 等子进程）
 *
 * 兼容链路（PhoneManager 17.1.6 / 17.3.10）：
 *   1. com.oplus.phonemanager.aivoicecalldetect.utils.AiDetectStatistics
 *        反诈埋点封装：主开关点击、事件计数、推送触达、误报反馈、VOIP 白名单外应用
 *        17.1.6 走 TrackApi.E；17.3.10 改走 TrackApi.track。
 *   2. com.oplus.phonemanager.aivoicecalldetect.utils.AiVoiceDetectDCSUtils
 *        17.1.6：m(int,String,boolean,String,String) / n(Context)
 *        17.3.10：uploadDetectInfo(...) / uploadDetectInfo$default(...) / uploadSwitches(Context)
 *        云端 DCS 上报：号码、包名、通话时长、检测时长、判定码 —— 最敏感的一跳
 *   3. com.oplus.nearx.track.TrackApi
 *        旧版 E(String appId, String event, Map params)
 *        新版 track(String eventGroup, String eventId, ...)，实例来自 getInstance(20092L)
 *        仅拦截第一个参数为 "20092" 且事件名命中反诈规则的调用。
 *
 * 保留不动（本地功能必须照常）：
 *   RiskWarningUtilsKt.insertFraudDetectSecurityEvent      -> 本地反诈记录落库
 *   FraudDetectNotificationHelperKt.showRiskNotification   -> 本地风险通知
 *   FraudRiskDialogActivity / FraudDetectNotificationHelperKt -> 本地弹窗
 *   AiVoiceDetectForegroundService / SecurityEventStatisticsVM -> 本地检测服务与记录页
 */
public class MainHook extends XposedModule {

    private static final String TAG = "AntiFraudBlock";
    private static final String PKG = "com.coloros.phonemanager";
    private static final String TRACK_APP_ID = "20092";

    /** 逃生阀：存在该文件则本次注入全部空转（改完 kill 掉手机管家即恢复默认行为） */
    private static final String KILL_SWITCH = "/data/local/tmp/afb.disable";

    private static boolean sDisabled = false;

    private static final String[] BLOCK_PREFIX = {"anti_fraud", "ai_detect", "fraud"};

    private static final Set<String> BLOCK_EXACT = new HashSet<>();

    static {
        BLOCK_EXACT.add("anti_fraud_switches");
        BLOCK_EXACT.add("anti_fraud_events_count");
        BLOCK_EXACT.add("anti_fraud_master_switche");
        BLOCK_EXACT.add("anti_fraud_handle_event");
        BLOCK_EXACT.add("anti_fraud_false_report_event");
        BLOCK_EXACT.add("ai_detect_result_event");
        BLOCK_EXACT.add("ai_detect_voip_app_outside_lists");
    }

    public MainHook() {
        super();
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log("module loaded, api=" + getApiVersion() + " framework=" + getFrameworkName());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (pkg == null || !pkg.startsWith(PKG)) return;

        sDisabled = new File(KILL_SWITCH).exists();
        log("attach " + pkg + (sDisabled ? " (kill switch ON, no-op)" : ""));

        ClassLoader cl = param.getDefaultClassLoader();

        // 1) 反诈埋点封装：所有 static void 全短路（返回 String 的 a(ZZZ) 不动）
        hookStaticVoid(cl,
                "com.oplus.phonemanager.aivoicecalldetect.utils.AiDetectStatistics", null);

        // 2) DCS 云端上报：同时覆盖 17.1.6 的 m/n 与 17.3.10 的新方法。
        // $default 是 Kotlin 默认参数桥，外部调用可能先进入它，必须一并覆盖。
        Set<String> dcs = new HashSet<>();
        dcs.add("m");
        dcs.add("n");
        dcs.add("uploadDetectInfo");
        dcs.add("uploadDetectInfo$default");
        dcs.add("uploadSwitches");
        hookStaticVoid(cl,
                "com.oplus.phonemanager.aivoicecalldetect.utils.AiVoiceDetectDCSUtils", dcs);

        // 3) 最终出口兜底：同时覆盖旧版 TrackApi.E 与新版 TrackApi.track。
        hookTrackSink(cl);

        log("kept local: insertFraudDetectSecurityEvent / showRiskNotification / FraudRiskDialogActivity");
    }

    @SuppressWarnings("unused")
    private void hookStaticVoid(ClassLoader cl, String clsName, final Set<String> names) {
        try {
            Class<?> c = Class.forName(clsName, false, cl);
            int hit = 0;
            for (final Method m : c.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                if (m.getReturnType() != void.class) continue;
                if (names != null && !names.contains(m.getName())) continue;
                hookMethod(m, "silence " + clsName + "#" + m.getName() + "/" + m.getParameterTypes().length, true);
                hit++;
            }
            log(clsName + " -> " + hit + " method(s) silenced");
        } catch (Throwable t) {
            log("hook class failed: " + clsName + " : " + t);
        }
    }

    @SuppressWarnings("unused")
    private void hookTrackSink(ClassLoader cl) {
        final String cls = "com.oplus.nearx.track.TrackApi";
        try {
            Class<?> c = Class.forName(cls, false, cl);
            int legacyHit = 0;
            int modernHit = 0;
            for (Method m : c.getDeclaredMethods()) {
                final boolean legacy = "E".equals(m.getName());
                final boolean modern = "track".equals(m.getName());
                if (!legacy && !modern) continue;

                Class<?>[] ps = m.getParameterTypes();
                if (ps.length < 2) continue;
                if (ps[0] != String.class || ps[1] != String.class) continue;
                if (m.getReturnType() != void.class) continue;
                // 旧版保持原来的三参数签名；新版覆盖全部 track 重载。
                if (legacy && ps.length != 3) continue;

                hookMethod(m, "sink TrackApi." + m.getName(), null);
                if (legacy) {
                    legacyHit++;
                } else {
                    modernHit++;
                }
            }
            log("TrackApi.E -> " + legacyHit + " sink hooked");
            log("TrackApi.track -> " + modernHit + " sink hooked");
        } catch (Throwable t) {
            log("hook TrackApi failed: " + t);
        }
    }

    /**
     * @param always  true=无条件吞掉返回值；null=按事件名判定（TrackApi.E/track 用）；false=不动
     */
    private void hookMethod(Executable exe, final String label, final Boolean always) {
        try {
            hook(exe).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (sDisabled) return chain.proceed();
                    if (always == null) {
                        // 旧版第一个参数是 appId；新版是 eventGroup，反诈链路两者均为 20092。
                        Object group = chain.getArg(0);
                        Object ev = chain.getArg(1);
                        if (ev instanceof String
                                && TRACK_APP_ID.equals(String.valueOf(group))
                                && isBlockedEvent((String) ev)) {
                            log("blocked track event: " + ev);
                            return null;
                        }
                        return chain.proceed();
                    }
                    if (always) {
                        log("hit " + label);
                        return null;
                    }
                    return chain.proceed();
                }
            });
        } catch (HookFailedError e) {
            log("hook failed " + label + " : " + e);
        } catch (Throwable t) {
            log("hook error " + label + " : " + t);
        }
    }

    private static boolean isBlockedEvent(String ev) {
        if (ev == null || ev.isEmpty()) return false;
        if (BLOCK_EXACT.contains(ev)) return true;
        String low = ev.toLowerCase(Locale.ROOT);
        for (String p : BLOCK_PREFIX) {
            if (low.startsWith(p)) return true;
        }
        return false;
    }

    private void log(String s) {
        try {
            Log.i(TAG, s);
        } catch (Throwable ignore) {
        }
        try {
            this.log(4, TAG, s);
        } catch (Throwable ignore) {
        }
    }
}
