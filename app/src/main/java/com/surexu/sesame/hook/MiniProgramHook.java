package com.surexu.sesame.hook;

import com.surexu.sesame.data.AppConfig;
import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.XHelpers;
import com.surexu.sesame.util.compat.XC_MethodHook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 小程序网络抓包（游戏等小程序数据），移植自上游 1.8.9.1；Sure-Xu 对齐自 Sesame-M v1.4.5。
 *
 * <p>小程序插件 dex 中的网络类延迟加载，故同时 hook 直接类与 {@code ClassLoader.loadClass}。
 * 只读记录，不修改请求参数与响应内容。
 *
 * <p>开关口径（v3.5.7 统一）：<b>本类不再自行判定开关</b>，安装与否由 {@code ApplicationHook}
 * 的抓包总开关（「开启抓包(基于新接口)」/「抓包记录」）决定，记录是否写出由 {@code Log.debug}
 * 按「抓包记录」统一门控。原先 {@code install()} 里另有一道 {@code miniDebugEnabled()} 早退，
 * 与上层判据不同源，会出现「上层判定通过、本类静默 return」——小程序抓包整条链路装不上。
 */
public final class MiniProgramHook {

    private MiniProgramHook() {
    }

    private static final String MINI_RESP_CLASS =
            "com.alipay.android.phone.event.ariver.network.HttpRequestResponseTrigger";
    private static final String ASYNC_REQUEST_HANDLER_CLASS =
            "com.alipay.android.phone.wallet.aompnetwork.request.AsyncRequestHandler";

    /** 上述两个定点类所在包：类名别名兜底时用于限定匹配范围，避免误挂无关同名类 */
    private static final String MINI_RESP_PACKAGE_PREFIX = "com.alipay.android.phone.event.ariver.network.";
    private static final String MINI_ASYNC_PACKAGE_PREFIX = "com.alipay.android.phone.wallet.aompnetwork.request.";

    /** 单条日志最大长度，防止走子记录等超长内容刷爆日志 */
    private static final int MAX_LOG_LEN = 20000;

    /** 非业务域名：日志埋点/监控/小程序包静态资源 CDN，命中即不记录 */
    private static final String[] MINI_HOST_BLACKLIST = {
            "hybrid.alipay-eco.com", "xxl-log", "mmstat", "amdc.", "arms",
            "alicdn.com", "tbcdn.cn", "alipayobjects.com", "gtimg.cn"
    };

    /** 静态资源后缀（按 ? 之前的路径判断）：命中即不记录。
     *  <p>注意：{@code .json} 已移出本黑名单——小程序业务接口大量以 {@code .json} 结尾
     *  （如 {@code .../gameInfo.json}），把它当静态资源过滤掉是「抓不到游戏接口」的主因之一。 */
    private static final String[] MINI_STATIC_SUFFIX = {
            ".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico",
            ".svg", ".woff", ".woff2", ".ttf", ".html", ".htm", ".mp4", ".mp3",
            // 游戏引擎资源：Unity/白鹭的 AssetBundle、贴图清单、骨骼动画等，实测占抓包量 90%+
            ".txt", ".ab", ".dat", ".bin", ".bytes", ".atlas", ".skel", ".plist", ".prefab", ".asset"
    };

    /** 游戏资源目录：命中即不记录（CDN 路径里 Assets/ab 包占绝大多数） */
    private static final String[] MINI_STATIC_PATH = {
            "/assets/", "/res/", "/static/", "/textures/", "/audio/"
    };

    /** 已 hook 过的类名（跨 loadClass 多次回调去重） */
    private static final Set<String> deferredHookedClasses =
            Collections.synchronizedSet(new HashSet<String>());

    /** 已暂存未配对请求：精确 URL -> {时间, 方法行, 请求体}（LRU 上限，防止无响应请求堆积） */
    private static final Map<String, String[]> pendingMiniRequests =
            Collections.synchronizedMap(new LinkedHashMap<String, String[]>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String[]> eldest) {
                    return size() > 128;
                }
            });

    /** 各类实例字段缓存（含父类链），避免网络热路径重复反射 */
    private static final Map<Class<?>, List<Field>> modelFieldsCache = new ConcurrentHashMap<>();

    private static volatile ClassLoader sClassLoader = null;
    private static volatile String sProcessName = "";

    /** ClassLoader.loadClass 延迟监听是否已安装（install 会被反复调用，全局热路径 hook 不得叠加） */
    private static volatile boolean sDeferredHooked = false;

    /** 已安装的 unhook 句柄，便于「抓包记录」关闭时卸载 */
    private static final List<XC_MethodHook.Unhook> sUnhooks =
            Collections.synchronizedList(new ArrayList<XC_MethodHook.Unhook>());

    /**
     * 安装小程序抓包 hook。由 {@code ApplicationHook} 在宿主进程 attach 后调用。
     * <p>此处不判定开关：安装与否由 {@code ApplicationHook} 的抓包总开关决定，记录可见性由
     * {@code Log.debug} 决定。原先这里另有一道 {@code miniDebugEnabled()} 早退，与上层判据不同源
     * （上层含「开启抓包(基于新接口)」、这里只看「抓包记录」），会造成「上层通过、这里静默 return」。
     */
    public static synchronized void install(ClassLoader classLoader, String processName) {
        sClassLoader = classLoader;
        sProcessName = processName == null ? "" : processName;
        boolean respHooked = tryHookMiniNetworkClass(MINI_RESP_CLASS);
        boolean asyncHooked = tryHookMiniNetworkClass(ASYNC_REQUEST_HANDLER_CLASS);
        installDeferredNetworkHook();
        // 两个定点类此时通常尚未加载（respHooked=false 属正常），真正安装发生在 loadClass 回调里；
        // 这行用于区分「没装上」与「装上后被过滤」，排查抓包缺失先看它
        Log.debug("小程序抓包hook装配: onHandleResponse=" + respHooked
                + " processAsyncRequest=" + asyncHooked + " 进程=" + sProcessName);
    }

    /** 卸载已安装的 hook；「抓包记录」关闭时调用 */
    public static synchronized void uninstall() {
        synchronized (sUnhooks) {
            for (XC_MethodHook.Unhook unhook : sUnhooks) {
                try {
                    unhook.unhook();
                } catch (Throwable ignored) {
                }
            }
            sUnhooks.clear();
        }
        deferredHookedClasses.clear();
        sDeferredHooked = false;
    }

    private static boolean tryHookMiniNetworkClass(String className) {
        if (deferredHookedClasses.contains(className)) {
            return true;
        }
        if (sClassLoader == null) {
            // 本地 XHelpers.findClassIfExists 内部是 loader.loadClass，传 null 必 NPE 且会被吞掉
            return false;
        }
        try {
            Class<?> clazz = XHelpers.findClassIfExists(className, sClassLoader);
            if (clazz != null) {
                hookMiniNetworkClass(clazz);
                return deferredHookedClasses.contains(className);
            }
        } catch (Throwable ignored) {
            // 类尚未加载，交给 loadClass 延迟监听
        }
        return false;
    }

    /** 请求暂存：域名白名单过滤后按精确 URL 保存，等待 onHandleResponse 配对（只读，不修改原请求） */
    private static void pendingMiniRequest(String url, String method, String data) {
        if (url == null || url.length() == 0 || !isGameApi(url)) {
            return;
        }
        String body = "";
        if (data != null && data.length() > 0) {
            body = data;
            // rantu 走 application/x-www-form-urlencoded：data=<urlencoded JSON>；
            // gamesapi2 等接口直接是裸 JSON，不能 URLDecoder 解码（'+' 会被误转为空格）
            if (body.startsWith("data=")) {
                body = body.substring(5);
                try {
                    body = URLDecoder.decode(body, "UTF-8");
                } catch (Throwable ignored) {
                }
            }
            body = clip(deepPrettyJson(body));
        }
        // timestamp+sign 使每次请求的完整 URL 唯一，天然可作为请求/响应配对键
        pendingMiniRequests.put(url, new String[]{
                String.valueOf(System.currentTimeMillis()),
                "MINI " + (method == null || method.length() == 0 ? "?" : method) + " " + url,
                body
        });
    }

    /** 记录小程序响应：与暂存请求按 URL 配对，按 时间/方法/参数/数据 单条输出，只保留游戏业务接口 */
    private static void logMiniResponse(Object[] args, Object thisObject) {
        if (!miniDebugEnabled() || args == null || args.length < 2 || args[0] == null) {
            return;
        }
        String url = args[0].toString();
        if (!isGameApi(url)) {
            return;
        }
        ensureAsyncRequestHook(thisObject != null ? thisObject.getClass().getClassLoader() : sClassLoader);
        String body = args[1] != null ? clip(deepPrettyJson(args[1].toString())) : "";
        String[] record = pendingMiniRequests.remove(url);
        String time;
        String methodLine;
        String params;
        if (record != null) {
            time = record[0];
            methodLine = record[1];
            params = record[2];
        } else {
            // 请求先于 hook 安装等情况下没有暂存，仍单独记录响应
            time = String.valueOf(System.currentTimeMillis());
            methodLine = "MINI ? " + url;
            params = "";
        }
        Log.debug("记录\n时间: " + time + "\n方法: " + methodLine + "\n参数: " + params + "\n数据: " + body + "\n");
    }

    /**
     * 响应首包到达时兜底补装 AsyncRequestHandler hook（正常情况下类加载时已在
     * hookMiniNetworkClass 中装好；此处防止 loadClass 监听漏回调）。
     */
    private static void ensureAsyncRequestHook(ClassLoader loader) {
        if (deferredHookedClasses.contains(ASYNC_REQUEST_HANDLER_CLASS)) {
            return;
        }
        try {
            Class<?> clazz = Class.forName(ASYNC_REQUEST_HANDLER_CLASS, false, loader);
            if (!deferredHookedClasses.add(ASYNC_REQUEST_HANDLER_CLASS)) {
                return;
            }
            installAsyncRequestHookOnClass(clazz);
        } catch (Throwable ignored) {
            // 类尚未加载：loadClass 监听命中后会安装，这里不打日志避免噪音
        }
    }

    /**
     * 响应侧兜底补装：HttpRequestResponseTrigger 只有 onHandleResponse 一条入口，没有第二条路。
     * 用请求线程真实的插件 ClassLoader 加载（注入进程自定义 loader 可能找不到插件类），
     * 命中后交给 {@link #hookMiniNetworkClass} 统一去重安装。
     */
    private static void ensureRespHook(ClassLoader loader) {
        if (deferredHookedClasses.contains(MINI_RESP_CLASS)) {
            return;
        }
        ClassLoader target = loader != null ? loader : sClassLoader;
        if (target == null) {
            return;
        }
        try {
            Class<?> clazz = Class.forName(MINI_RESP_CLASS, false, target);
            if (clazz != null) {
                hookMiniNetworkClass(clazz);
            }
        } catch (Throwable ignored) {
            // 类尚未加载：loadClass 监听命中后会安装，这里不打日志避免噪音
        }
    }

    /** 取参数对象自身的 ClassLoader：小程序插件类由插件 dex 的 loader 加载，优先用它。 */
    private static ClassLoader resolveClassLoader(Object[] args) {
        for (Object arg : args) {
            if (arg != null && arg.getClass().getClassLoader() != null) {
                return arg.getClass().getClassLoader();
            }
        }
        return sClassLoader;
    }

    /**
     * 定点 hook 真实请求入口（由响应调用栈与方法清单确认）：
     * AsyncRequestHandler.processAsyncRequest(ApiContext,App,Page,RequestModel,ParamsModel,...,String,...)
     * paramsJson 在到达此处前已被拆进 RequestModel/ParamsModel 的字段，故递归收集模型中的字符串。
     */
    private static void installAsyncRequestHookOnClass(Class<?> clazz) {
        // processAsyncRequest 可能声明在父类上（或存在多个重载）：本类没挂到就沿继承链向上补一层，
        // 否则会出现「类装上了、方法 0 个」的静默失效
        XC_MethodHook requestHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    logRequestFromModels(p.args);
                } catch (Throwable ignored) {
                }
            }
        };
        int hooked = hookAllMethodsByName(clazz, "processAsyncRequest", requestHook);
        Class<?> parent = clazz.getSuperclass();
        while (hooked == 0 && parent != null && parent != Object.class) {
            hooked += hookAllMethodsByName(parent, "processAsyncRequest", requestHook);
            parent = parent.getSuperclass();
        }
        if (hooked > 0) {
            Log.debug("小程序抓包hook已安装: " + clazz.getName()
                    + " processAsyncRequest方法=" + hooked + " 进程=" + sProcessName);
        } else {
            Log.debug("小程序抓包hook未命中: " + clazz.getName()
                    + " 无 processAsyncRequest（需核对支付宝版本）");
        }
    }

    /** 从 processAsyncRequest 的模型参数中提取游戏请求（先找完整 paramsJson，再按 url/body 拼装） */
    private static void logRequestFromModels(Object[] args) {
        if (args == null) {
            return;
        }
        // 响应侧只有 onHandleResponse 一条入口：一旦 loadClass 监听漏回调，就只记得到参数、记不到响应。
        // 放在开关判定之前，保证「先跑请求、后开开关」时也能补装
        ensureRespHook(resolveClassLoader(args));
        if (!miniDebugEnabled()) {
            return;
        }
        List<String> strings = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        for (Object arg : args) {
            collectStrings(arg, 3, visited, strings);
            if (strings.size() > 256) {
                break;
            }
        }
        // 1) 模型里若仍保留完整 paramsJson
        for (String s : strings) {
            if (s.length() >= 8 && s.indexOf('"') >= 0) {
                try {
                    JSONObject jo = new JSONObject(s);
                    String u = jo.optString("url");
                    if (u.length() > 0 && isGameApi(u)) {
                        pendingMiniRequest(u, jo.optString("method", "GET"), jo.optString("data"));
                        return;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        // 2) url 与 body 被拆成独立字段：模型里可能有多个 URL（referer/广告配置等），
        //    给所有候选 URL 登记同一份暂存，响应回来时精确 URL 自然命中真实请求
        String method = null;
        for (String s : strings) {
            if ("GET".equals(s) || "POST".equals(s) || "PUT".equals(s) || "DELETE".equals(s)) {
                method = s;
                break;
            }
        }
        String body = null;
        for (String s : strings) {
            if (s.startsWith("data=")) {
                body = s;
                break;
            }
        }
        if (body == null) {
            for (String s : strings) {
                if (s.startsWith("{") && s.length() > 16 && (body == null || s.length() > body.length())) {
                    body = s;
                }
            }
        }
        Set<String> candidates = new LinkedHashSet<>();
        for (String s : strings) {
            if (s.startsWith("http") && isGameApi(s)) {
                candidates.add(s.trim());
                if (candidates.size() >= 3) {
                    break;
                }
            }
        }
        for (String url : candidates) {
            pendingMiniRequest(url, method, body);
        }
    }

    /**
     * 递归收集对象图中的 String：字段读取含父类链，com.alipay 模型对象继续下钻，
     * Map/Collection 取元素，深度与对象数双重上限，只读。
     */
    private static void collectStrings(Object obj, int depth, Set<Object> visited, List<String> out) {
        if (obj == null || depth < 0 || visited.size() > 128) {
            return;
        }
        if (obj instanceof String) {
            String s = (String) obj;
            if (s.length() > 0) {
                out.add(s);
            }
            return;
        }
        Class<?> c = obj.getClass();
        if (c.isPrimitive() || c.isArray() || obj instanceof Number || obj instanceof Boolean
                || obj instanceof Character || obj instanceof Enum) {
            return;
        }
        if (obj instanceof Map) {
            if (!visited.add(obj)) {
                return;
            }
            for (Object v : ((Map<?, ?>) obj).values()) {
                collectStrings(v, depth - 1, visited, out);
            }
            return;
        }
        if (obj instanceof Collection) {
            if (!visited.add(obj)) {
                return;
            }
            for (Object v : (Collection<?>) obj) {
                collectStrings(v, depth - 1, visited, out);
            }
            return;
        }
        if (!c.getName().startsWith("com.alipay") || !visited.add(obj)) {
            return;
        }
        for (Field f : modelFieldsCache.computeIfAbsent(c, MiniProgramHook::resolveInstanceFields)) {
            try {
                collectStrings(f.get(obj), depth - 1, visited, out);
            } catch (Throwable ignored) {
            }
        }
    }

    private static List<Field> resolveInstanceFields(Class<?> clazz) {
        List<Field> list = new ArrayList<>();
        Class<?> cur = clazz;
        int guard = 0;
        while (cur != null && cur != Object.class && guard++ < 6) {
            for (Field fld : cur.getDeclaredFields()) {
                int mods = fld.getModifiers();
                if (Modifier.isStatic(mods)) {
                    continue;
                }
                Class<?> t = fld.getType();
                if (t == String.class || t.getName().startsWith("com.alipay")
                        || Map.class.isAssignableFrom(t) || Collection.class.isAssignableFrom(t)) {
                    try {
                        fld.setAccessible(true);
                        list.add(fld);
                    } catch (Throwable ignored) {
                    }
                }
            }
            cur = cur.getSuperclass();
        }
        return list;
    }

    /**
     * 是否记录该小程序请求：抓包开关打开期间默认记录全部小程序 API 请求，
     * 仅排除埋点/日志/监控域名和静态资源，避免白名单域名导致其他游戏抓不到。
     */
    private static boolean isGameApi(String url) {
        if (url == null || url.length() < 8) {
            return false;
        }
        String lower = url.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false;
        }
        for (String host : MINI_HOST_BLACKLIST) {
            if (lower.contains(host)) {
                return false;
            }
        }
        int query = lower.indexOf('?');
        String path = query >= 0 ? lower.substring(0, query) : lower;
        int slash = path.indexOf('/', 8);
        if (slash >= 0) {
            path = path.substring(slash);
        }
        for (String suffix : MINI_STATIC_SUFFIX) {
            if (path.endsWith(suffix)) {
                return false;
            }
        }
        for (String dir : MINI_STATIC_PATH) {
            if (path.contains(dir)) {
                return false;
            }
        }
        return true;
    }

    /** 把 JSON 中嵌套转义的 JSON 字符串逐层展开为单行紧凑形式；非 JSON 原样返回并折掉换行 */
    private static String deepPrettyJson(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        try {
            Object parsed;
            if (s.startsWith("{")) {
                parsed = deepParseJson(new JSONObject(s));
            } else if (s.startsWith("[")) {
                parsed = deepParseJson(new JSONArray(s));
            } else {
                return raw.replaceAll("\\r?\\n", " ");
            }
            // toString() 为单行紧凑输出，保证每条日志只占一行
            String out = (parsed instanceof JSONObject)
                    ? ((JSONObject) parsed).toString()
                    : ((JSONArray) parsed).toString();
            return out.replaceAll("\\r?\\n", " ");
        } catch (Throwable t) {
            return raw.replaceAll("\\r?\\n", " ");
        }
    }

    private static Object deepParseJson(Object value) {
        try {
            if (value instanceof JSONObject) {
                JSONObject obj = (JSONObject) value;
                JSONObject out = new JSONObject();
                JSONArray names = obj.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String key = names.optString(i);
                        out.put(key, deepParseJson(obj.opt(key)));
                    }
                }
                return out;
            }
            if (value instanceof JSONArray) {
                JSONArray arr = (JSONArray) value;
                JSONArray out = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    out.put(deepParseJson(arr.opt(i)));
                }
                return out;
            }
            if (value instanceof String) {
                String str = ((String) value).trim();
                boolean jsonObj = str.startsWith("{") && str.endsWith("}");
                boolean jsonArr = str.startsWith("[") && str.endsWith("]");
                if (jsonObj || jsonArr) {
                    return jsonObj ? deepParseJson(new JSONObject(str)) : deepParseJson(new JSONArray(str));
                }
            }
        } catch (Throwable ignored) {
        }
        return value;
    }

    private static String clip(String s) {
        if (s != null && s.length() > MAX_LOG_LEN) {
            return s.substring(0, MAX_LOG_LEN) + "...(truncated)";
        }
        return s;
    }

    /**
     * 监听 ClassLoader.loadClass：小程序插件 dex 中的网络类被加载时动态安装 hook。
     *
     * <p>只挂 {@code ClassLoader.loadClass(String)} 一处：子类（含 BaseDexClassLoader）重写的是
     * {@code loadClass(String, boolean)}，{@code loadClass(String)} 调用一律落到本方法，
     * 再挂一遍 BaseDexClassLoader 只会对同一批类加载重复回调、白付一次全局热路径开销。
     * <p>幂等：{@link #install} 会被主进程启动 / 子进程 / reloadConfig 反复调用，本方法不得重复挂载，
     * 否则每次开启抓包都在全局类加载热路径上再叠一层 hook 且永不回收。
     */
    private static void installDeferredNetworkHook() {
        if (sDeferredHooked) {
            return;
        }
        sDeferredHooked = true;
        XC_MethodHook loadClassHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Object result = p.getResult();
                    if (result instanceof Class) {
                        hookMiniNetworkClass((Class<?>) result);
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            addUnhook(XHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, loadClassHook));
        } catch (Throwable ignored) {
        }
    }

    /** 对小程序网络请求/响应类安装定点 hook（只记录，不修改参数和返回值） */
    private static void hookMiniNetworkClass(Class<?> clazz) {
        String name = clazz.getName();
        boolean exact = MINI_RESP_CLASS.equals(name) || ASYNC_REQUEST_HANDLER_CLASS.equals(name);
        boolean alias = !exact && isMiniNetworkAliasName(name);
        if (!exact && !alias) {
            return;
        }
        if (!deferredHookedClasses.add(name)) {
            return;
        }
        boolean asResponse = exact ? MINI_RESP_CLASS.equals(name) : isResponseAliasName(name);
        Log.debug("小程序抓包hook候选: " + name + (alias ? "(类名别名兜底)" : "") + " 进程=" + sProcessName);
        try {
            if (asResponse) {
                int hooked = hookMethodInHierarchy(clazz, "onHandleResponse", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            logMiniResponse(p.args, p.thisObject);
                        } catch (Throwable ignored) {
                        }
                    }
                });
                if (hooked > 0) {
                    Log.debug("小程序抓包hook已安装: " + name + " onHandleResponse方法=" + hooked + " 进程=" + sProcessName);
                } else {
                    Log.debug("小程序抓包hook未命中: " + name
                            + " 类上无 onHandleResponse（响应侧唯一入口，需核对支付宝版本）");
                }
            } else {
                installAsyncRequestHookOnClass(clazz);
            }
        } catch (Throwable t) {
            Log.debug("小程序抓包hook安装失败: " + name + " " + t.getMessage());
        }
    }

    /**
     * 类名别名兜底：支付宝升级后类名/包路径可能微调（历史上曾整体迁移过一次包路径）。
     * 只在精确类名未命中时启用，匹配条件是「同包路径 + 同名类尾」，避免误挂无关类。
     */
    private static boolean isMiniNetworkAliasName(String name) {
        if (name == null) {
            return false;
        }
        if (name.startsWith(MINI_RESP_PACKAGE_PREFIX) && name.endsWith("HttpRequestResponseTrigger")) {
            return true;
        }
        return name.startsWith(MINI_ASYNC_PACKAGE_PREFIX) && name.endsWith("AsyncRequestHandler");
    }

    private static boolean isResponseAliasName(String name) {
        return name != null && name.endsWith("HttpRequestResponseTrigger");
    }

    /**
     * 沿继承链逐层 hook 同名方法并返回 hook 到的方法总数。
     * onHandleResponse 可能定义在父类上，必须向上追溯。
     */
    private static int hookMethodInHierarchy(Class<?> clazz, String methodName, XC_MethodHook callback) {
        int count = 0;
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            count += hookAllMethodsByName(current, methodName, callback);
            current = current.getSuperclass();
        }
        return count;
    }

    /** 等价于 XposedBridge.hookAllMethods：遍历本类声明的同名方法逐个挂载 */
    private static int hookAllMethodsByName(Class<?> clazz, String methodName, XC_MethodHook callback) {
        int count = 0;
        for (Method m : clazz.getDeclaredMethods()) {
            if (!m.getName().equals(methodName)) {
                continue;
            }
            try {
                m.setAccessible(true);
                addUnhook(XHelpers.hookMember(m, callback));
                count++;
            } catch (Throwable ignored) {
            }
        }
        return count;
    }

    /**
     * 是否记录小程序抓包：沿用日志页「抓包记录」开关，与 RPC 抓包一致。
     * <p>走 {@code AppConfig.isDebugLogEnabled()}：内存值优先，为假时按 appConfig.json 时间戳
     * 惰性重读一次，使开关改动在收不到 reloadConfig 广播的注入子进程（小程序容器 :appbrand*）
     * 也能即时生效，无需重启小程序或支付宝。
     */
    private static boolean miniDebugEnabled() {
        try {
            return AppConfig.isDebugLogEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void addUnhook(XC_MethodHook.Unhook unhook) {
        if (unhook != null) {
            sUnhooks.add(unhook);
        }
    }
}
