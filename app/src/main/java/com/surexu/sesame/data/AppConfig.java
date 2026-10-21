package com.surexu.sesame.data;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import com.surexu.sesame.hook.ApplicationHook;
import com.surexu.sesame.util.ClassUtil;
import com.surexu.sesame.util.FileUtil;
import com.surexu.sesame.util.JsonUtil;
import com.surexu.sesame.util.Log;

import java.io.File;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Data
public class AppConfig {

    private static final String TAG = AppConfig.class.getSimpleName();

    // 存到与注入进程（支付宝模块）共享的 sesame 目录，确保日志开关在模块进程同样生效
    private static final File APP_CONFIG_DIRECTORY_FILE = FileUtil.MAIN_DIRECTORY_FILE;

    public static final AppConfig INSTANCE = new AppConfig();

    /** 上次 load 解析失败：内存此时只是默认值，必须禁止写盘，否则会把默认值整份固化 */
    private static volatile boolean loadFailed = false;

    /** 进程级加载标记：LanguageUtil/ThemeUtil 每页都会触发加载，仅首次真正读盘 */
    private static final AtomicBoolean configLoadedOnce = new AtomicBoolean(false);

    /** 上次成功读到的 appConfig.json lastModified：供跨进程惰性重读判断文件是否被别的进程改写 */
    private static volatile long lastLoadedModified = 0L;

    /** 异步落盘专用：单线程串行，避免并发写同一文件 */
    private static final ExecutorService SAVE_EXECUTOR = Executors.newSingleThreadExecutor();

    @JsonIgnore
    private boolean init;

    private Boolean newUI = true;
    private Boolean languageSimplifiedChinese = true;

    private Boolean darkMode = false;
    private Boolean followSystem = true;

    private Boolean enableForestLog = true;
    private Boolean enableGoldenBeansLog = true;
    private Boolean enableFarmLog = true;
    private Boolean enableOtherLog = true;
    private Boolean enableDebugLog = false;
    private Boolean enableViewErrorLog = true;
    private Boolean enableViewRuntimeLog = true;
    private Boolean batteryPerm = true;

    // 模块级开关：原先是按账号存在 BaseModel 里，现改为全局（注入进程与模块 App 共用同一份）
    private Boolean newRpc = true;
    private Boolean showToast = true;
    private Integer toastOffsetY = 0;
    private Boolean enableOnGoing = false;
    private Boolean closeCaptchaDialog = true;

    public Boolean getNewRpc() {
        return newRpc;
    }

    public void setNewRpc(Boolean value) {
        newRpc = value;
    }

    public Boolean getShowToast() {
        return showToast;
    }

    public void setShowToast(Boolean value) {
        showToast = value;
    }

    public Integer getToastOffsetY() {
        return toastOffsetY;
    }

    public void setToastOffsetY(Integer value) {
        toastOffsetY = value;
    }

    public Boolean getEnableOnGoing() {
        return enableOnGoing;
    }

    public void setEnableOnGoing(Boolean value) {
        enableOnGoing = value;
    }

    public Boolean getCloseCaptchaDialog() {
        return closeCaptchaDialog;
    }

    public void setCloseCaptchaDialog(Boolean value) {
        closeCaptchaDialog = value;
    }

    public Boolean getLanguageSimplifiedChinese() {
        return languageSimplifiedChinese;
    }

    public void setLanguageSimplifiedChinese(Boolean value) {
        languageSimplifiedChinese = value;
    }

    public Boolean getDarkMode() {
        return darkMode;
    }

    public void setDarkMode(Boolean value) {
        darkMode = value;
    }

    public Boolean getFollowSystem() {
        return followSystem;
    }

    public void setFollowSystem(Boolean value) {
        followSystem = value;
    }

    public Boolean getEnableForestLog() { return enableForestLog; }
    public void setEnableForestLog(Boolean value) { enableForestLog = value; }

    public Boolean getEnableGoldenBeansLog() { return enableGoldenBeansLog; }
    public void setEnableGoldenBeansLog(Boolean value) { enableGoldenBeansLog = value; }

    public Boolean getEnableFarmLog() { return enableFarmLog; }
    public void setEnableFarmLog(Boolean value) { enableFarmLog = value; }

    public Boolean getEnableOtherLog() { return enableOtherLog; }
    public void setEnableOtherLog(Boolean value) { enableOtherLog = value; }

    public Boolean getEnableDebugLog() { return enableDebugLog; }
    public void setEnableDebugLog(Boolean value) { enableDebugLog = value; }

    public Boolean getEnableViewErrorLog() { return enableViewErrorLog; }
    public void setEnableViewErrorLog(Boolean value) { enableViewErrorLog = value; }

    public Boolean getEnableViewRuntimeLog() { return enableViewRuntimeLog; }
    public void setEnableViewRuntimeLog(Boolean value) { enableViewRuntimeLog = value; }

    public Boolean getBatteryPerm() { return batteryPerm; }
    public void setBatteryPerm(Boolean value) { batteryPerm = value; }

    public static Boolean save() {
        if (loadFailed) {
            // 上次加载失败，内存只是默认值：写盘会把用户配置整份固化，拒绝
            Log.i(TAG, "上次APP配置加载失败，本次不写盘");
            return false;
        }
        return FileUtil.write2File(toSaveStr(), new File(APP_CONFIG_DIRECTORY_FILE, "appConfig.json"));
    }

    /** 进程内仅首次真正读盘加载；此后所有调用直接返回已加载实例，避免每页重复 IO。
     *  注入进程与模块 App 各持有本类的独立静态状态，两进程互不影响。 */
    public static AppConfig loadIfNeeded() {
        if (configLoadedOnce.compareAndSet(false, true)) {
            load();
        }
        return INSTANCE;
    }

    /**
     * 跨进程惰性重读开关：仅当 appConfig.json 的 lastModified 与上次读取不同时, 重新解析并把
     * 「日志/抓包」开关搬进内存; 不写盘、不做格式化回写、不改动其它配置字段。
     *
     * <p>存在意义：注入进程（尤其小程序容器子进程）收不到 UI 侧的 reloadConfig 广播, 内存里的开关
     * 会一直停在启动那一刻的值, 表现为「用户在设置里开了抓包记录, 小程序却一直没记录, 必须重启小程序」。
     * 本方法让开关改动按文件时间戳即时生效。
     *
     * @return 是否发生了重读且有字段变化
     */
    public static boolean refreshIfChanged() {
        try {
            File appConfigFile = new File(APP_CONFIG_DIRECTORY_FILE, "appConfig.json");
            if (!appConfigFile.exists() || !appConfigFile.canRead()) {
                return false;
            }
            long modified = appConfigFile.lastModified();
            if (modified == 0L || modified == lastLoadedModified) {
                return false;
            }
            String json = FileUtil.readFromFile(appConfigFile);
            if (json == null || json.isEmpty()) {
                return false;
            }
            // 解析成独立的树、只搬开关字段：半份/异常 JSON 不会污染内存里其它配置
            JsonNode node = JsonUtil.copyMapper().readTree(json);
            lastLoadedModified = modified;
            if (node == null) {
                return false;
            }
            boolean changed = false;
            JsonNode debugLogNode = node.get("enableDebugLog");
            if (debugLogNode != null && debugLogNode.isBoolean()) {
                Boolean value = debugLogNode.asBoolean();
                if (!value.equals(INSTANCE.getEnableDebugLog())) {
                    INSTANCE.setEnableDebugLog(value);
                    changed = true;
                }
            }
            JsonNode newRpcNode = node.get("newRpc");
            if (newRpcNode != null && newRpcNode.isBoolean()) {
                Boolean value = newRpcNode.asBoolean();
                if (!value.equals(INSTANCE.getNewRpc())) {
                    INSTANCE.setNewRpc(value);
                    changed = true;
                }
            }
            return changed;
        } catch (Throwable t) {
            // 读/解析失败保持内存现值；本方法在日志热路径上被调用, 不能抛异常、不能写日志(会递归)
            return false;
        }
    }

    /**
     * 「抓包记录」是否写出（抓包 hook 与运行日志共用）：内存值优先; 为假时按文件时间戳惰性
     * 重读一次, 保证跨进程（尤其收不到广播的注入子进程）即时生效。
     */
    public static boolean isDebugLogEnabled() {
        if (Boolean.TRUE.equals(INSTANCE.getEnableDebugLog())) {
            return true;
        }
        return refreshIfChanged() && Boolean.TRUE.equals(INSTANCE.getEnableDebugLog());
    }

    /** 异步落盘：调用线程（主线程）只做快照序列化，文件 IO 交给后台单线程执行；
     *  与 save() 同款 loadFailed 保护，失败时不写盘。 */
    public static void saveAsync() {
        if (loadFailed) {
            Log.i(TAG, "上次APP配置加载失败，本次不写盘");
            return;
        }
        final String json = toSaveStr();
        SAVE_EXECUTOR.execute(() -> FileUtil.write2File(json, new File(APP_CONFIG_DIRECTORY_FILE, "appConfig.json")));
    }

    public static synchronized AppConfig load() {
        File appConfigFile = new File(APP_CONFIG_DIRECTORY_FILE, "appConfig.json");
        loadFailed = false;
        try {
            if (appConfigFile.exists()) {
                String json = FileUtil.readFromFile(appConfigFile);
                JsonUtil.copyMapper().readerForUpdating(INSTANCE).readValue(json);
                lastLoadedModified = appConfigFile.lastModified();
                // 注意：必须先加载文件再打印日志，否则开关判断仍取默认值 true，
                // 导致「运行日志已关闭」时启动进程仍写入加载日志
                // 仅主进程记录：子进程(tools/push/gpu/sandboxed_privilege/syntax)注入也会调用 load(),
                // 不区分进程会在抓包记录里刷屏
                if (ClassUtil.PACKAGE_NAME.equals(ApplicationHook.processName)) {
                    Log.debug("加载APP配置");
                }
                String formatted = toSaveStr();
                if (formatted != null && !formatted.equals(json)) {
                    Log.i(TAG, "格式化APP配置");
                    FileUtil.write2File(formatted, appConfigFile);
                }
            } else {
                unload();
                Log.i(TAG, "初始APP配置");
                FileUtil.write2File(toSaveStr(), appConfigFile);
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
            // 解析失败只回落默认值、绝不写盘：瞬时 IO 抖动或半份文件不该把用户配置整份重置
            Log.i(TAG, "解析APP配置失败，本次使用默认值（不写盘）");
            loadFailed = true;
            unload();
        }
        INSTANCE.setInit(true);
        return INSTANCE;
    }

    public static synchronized void unload() {
        try {
            JsonUtil.copyMapper().updateValue(INSTANCE, new AppConfig());
        } catch (JsonMappingException e) {
            Log.printStackTrace(TAG, e);
        }
    }

    public static String toSaveStr() {
        return JsonUtil.toFormatJsonString(INSTANCE);
    }

}