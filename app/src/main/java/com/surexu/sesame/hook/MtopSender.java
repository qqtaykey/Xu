package com.surexu.sesame.hook;

import android.content.Context;

import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.StringUtil;
import com.surexu.sesame.util.XHelpers;

import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MTOP 主动请求基座。
 *
 * <p>Sure-Xu 原先只有「抓包 dump」hook(ApplicationHook 里 hook MtopBuilder 的
 * syncRequest/asyncRequest 与静态 {@code Mtop.build}), 业务层无法自己发 MTOP 请求。
 * 芝麻狗/芝麻粒游乐记等 alisports 活动只提供 MTOP 接口, 因此这里反射复用宿主
 * (支付宝进程内)已经存在的 mtopsdk 实例: 宿主实例自带 appKey/会话/设备环境/安全签名
 * (wua、asac), 比自建实例可靠得多, 也不需要 root 或独立签名能力。
 *
 * <p>获取宿主 Mtop 实例的三条路径, 按可靠性排序:
 * <ol>
 *   <li>dump hook 顺手捕获({@link #captureBuilder}): 容器自己用的那个实例, 最可靠;</li>
 *   <li>{@code mtopsdk.mtop.intf.Mtop} 静态字段(含静态 Map)里的单例;</li>
 *   <li>{@code Mtop.instance(...)} 各签名兜底新建。</li>
 * </ol>
 *
 * <p>线程约定: {@code syncRequest} 是阻塞调用, 必须在后台线程使用(ModelTask 的任务线程即可)。
 */
public final class MtopSender {

    private MtopSender() {
    }

    private static final String TAG = "MtopSender";

    private static final String CLS_MTOP = "mtopsdk.mtop.intf.Mtop";
    private static final String CLS_BUILDER = "mtopsdk.mtop.intf.MtopBuilder";
    private static final String CLS_REQUEST = "mtopsdk.mtop.domain.MtopRequest";
    /** 宿主 JS 侧统一传的 isv 开放 appkey(抓包可见) */
    private static final String ISV_OPEN_APPKEY = "DEFAULT_AUTH";
    private static final int DEFAULT_TIMEOUT_MS = 15000;
    /** 头字段扫描时判定「像是请求头容器」的键特征 */
    private static final String[] HEADER_KEY_HINTS = new String[]{"alty", "asac", "wua", "cookie", "umt", "x-sgext", "sg"};

    /** 宿主容器自己的 Mtop 实例(dump hook 捕获: 签名/会话/设备环境都是现成的) */
    private static volatile Object mtopInstance;
    /** 宿主 builder 用的 ttid(调接口时能少一次环境探测) */
    private static volatile String ttid = "";
    private static volatile Object templateBuilder;
    private static volatile Class<?> templateRequestClazz;
    /** 宿主真实发出去过的请求头(含 asac/wua 等安全头), 按 api 记账, SDK 不自动注入时兜底复用 */
    private static final Map<String, Map<String, String>> OBSERVED_HEADERS = new ConcurrentHashMap<>();
    private static volatile Map<String, String> lastObservedHeaders;
    /** 只读诊断报告的缓存(实例未捕获时每次都能重算, 不缓存) */
    private static volatile String lastProbe = "";
    /** 标记「本模块自己发的请求」, 避免 dump hook 把我们自己的空壳 builder 当成宿主模板 */
    private static final ThreadLocal<Boolean> SELF_CALL = new ThreadLocal<>();

    // ═══════════════════════════ 对外 API ═══════════════════════════

    /** 当前线程是否正在执行本基座发出的请求(dump hook 用来打标, 也用来跳过模板捕获)。 */
    public static boolean isSelfCall() {
        return Boolean.TRUE.equals(SELF_CALL.get());
    }

    /**
     * 发一个 MTOP 请求(阻塞)。必须后台线程调用。
     *
     * @param apiName  接口名, 如 mtop.alisports.play.sesamedog.forward
     * @param version  版本, 如 1.0
     * @param dataText 业务参数 JSON 字符串
     * @param needWua  是否需要 wua 签名(forward 类接口必须 true)
     * @param headers  额外请求头(如 alty_ext / alty_sso_token), 可为 null
     */
    public static Result send(String apiName, String version, String dataText, boolean needWua,
                              Map<String, String> headers) {
        SELF_CALL.set(Boolean.TRUE);
        try {
            Object mtop = resolveMtop();
            if (mtop == null) {
                return fail(apiName, "未捕获到宿主 Mtop 实例(需宿主先发过一次 MTOP 请求; 下次执行会自动重试)", "");
            }
            Object request = newRequest(apiName, version, dataText);
            if (request == null) {
                return fail(apiName, "构造 MtopRequest 失败", probe());
            }
            Object builder = newBuilder(mtop, request);
            if (builder == null) {
                return fail(apiName, "构造 MtopBuilder 失败", probe());
            }
            List<String> notes = new ArrayList<>();
            applyConfig(builder, request, needWua, notes);
            Map<String, String> first = new LinkedHashMap<>();
            if (headers != null) {
                first.putAll(headers);
            }
            String headerNote = applyHeaders(builder, request, first);
            Log.debug("[" + TAG + "] → " + apiName + " v" + version + " needWua=" + needWua
                    + " headers=" + maskMap(first) + " data=" + truncate(dataText, 400)
                    + " | " + headerNote + " | cfg=" + join(notes));

            Result result = doSend(builder, apiName, joinNotes(notes, headerNote));
            if (result.ok || !needWua || !result.isSecurityFail()) {
                return result;
            }
            // 安全头缺失(宿主未自动注入 asac/wua)时: 用宿主真实请求头再补一次
            Map<String, String> observed = observedHeadersFor(apiName);
            if (observed == null || observed.isEmpty()) {
                return result;
            }
            Log.record("芝麻狗签名重试: 首次返回 " + result.brief() + ", 改用宿主请求头重发");
            Object builder2 = newBuilder(mtop, request);
            if (builder2 == null) {
                return result;
            }
            List<String> notes2 = new ArrayList<>();
            applyConfig(builder2, request, needWua, notes2);
            Map<String, String> retryHeaders = new LinkedHashMap<>(observed);
            if (headers != null) {
                retryHeaders.putAll(headers);
            }
            String headerNote2 = applyHeaders(builder2, request, retryHeaders);
            Result retry = doSend(builder2, apiName, joinNotes(notes2, headerNote2));
            return retry.ok ? retry : result;
        } catch (Throwable t) {
            Log.err(TAG, "send " + apiName + " err:", t);
            return fail(apiName, "异常: " + t, "");
        } finally {
            SELF_CALL.remove();
        }
    }

    /**
     * dump hook 回调: 从宿主真实使用的 MtopBuilder 上捕获 Mtop 实例 / ttid / 请求头。
     * 只做一次, 开销可忽略; 任何异常都吞掉, 绝不干扰宿主请求。
     */
    public static void captureBuilder(Object builder, Object... extraArgs) {
        if (builder == null || isSelfCall()) {
            return;
        }
        try {
            String cn = builder.getClass().getName();
            if (!CLS_BUILDER.equals(cn) && !cn.startsWith(CLS_BUILDER)) {
                return;
            }
            if (templateBuilder == null) {
                templateBuilder = builder;
            }
            if (StringUtil.isEmpty(ttid)) {
                Object v = tryInvoke(builder, "getTtid");
                if (v == null) {
                    v = readFieldByName(builder, "ttid");
                }
                if (v != null && !String.valueOf(v).isEmpty()) {
                    ttid = String.valueOf(v);
                }
            }
            if (mtopInstance == null) {
                captureInstance(findMtop(builder, 2, new int[]{60}));
            }
            if (mtopInstance == null && extraArgs != null) {
                for (Object arg : extraArgs) {
                    if (isMtop(arg)) {
                        captureInstance(arg);
                        break;
                    }
                }
            }
            if (templateRequestClazz == null) {
                Object req = findRequest(builder);
                if (req != null) {
                    templateRequestClazz = req.getClass();
                }
            }
            String api = readApi(builder);
            if (!api.isEmpty() && !OBSERVED_HEADERS.containsKey(api)) {
                Map<String, String> h = readHeaderMap(builder);
                if (h.isEmpty()) {
                    // 有些宿主版本把 JS 侧整包参数塞进 request.data, headers 藏在里面的 "headers" 字段
                    h = headersFromRequestData(builder);
                }
                Map<String, String> sec = securityHeaders(h);
                if (!sec.isEmpty()) {
                    OBSERVED_HEADERS.put(api, sec);
                    lastObservedHeaders = sec;
                }
            }
        } catch (Throwable ignore) {
            // 捕获失败不影响宿主, 也不影响后续重试
        }
    }

    /** 只读状态摘要, 给任务日志用。 */
    public static String stateText() {
        return "MTOP基座[" + (isMtop(mtopInstance) ? "实例已捕获" : "实例未捕获")
                + ", ttid=" + (StringUtil.isEmpty(ttid) ? "-" : ttid)
                + ", 模板builder=" + (templateBuilder != null ? "有" : "无")
                + ", 已观测请求头的接口=" + OBSERVED_HEADERS.size() + "个]";
    }

    /**
     * 反射诊断报告: 列出实例/ttid/候选方法签名。真机排障时把它读出来就能定位反射落点问题。
     */
    public static String probe() {
        try {
            StringBuilder sb = new StringBuilder("[MtopSender 诊断]");
            sb.append("\n实例=").append(isMtop(mtopInstance) ? mtopInstance.getClass().getName() : "未捕获");
            sb.append(" ttid=").append(ttid).append(" 模板builder=").append(templateBuilder != null);
            sb.append(" 观测接口数=").append(OBSERVED_HEADERS.size());
            Class<?> mc = classIfExists(CLS_MTOP);
            if (mc != null) {
                appendMembers(sb, "Mtop", mc);
            }
            Class<?> bc = classIfExists(CLS_BUILDER);
            if (bc != null) {
                appendMembers(sb, "MtopBuilder", bc);
            }
            Class<?> rc = templateRequestClazz != null ? templateRequestClazz : classIfExists(CLS_REQUEST);
            if (rc != null) {
                appendMembers(sb, "MtopRequest", rc);
            }
            lastProbe = sb.toString();
            return lastProbe;
        } catch (Throwable t) {
            return "[MtopSender 诊断] 生成失败: " + t;
        }
    }

    // ═══════════════════════════ 实例解析 ═══════════════════════════

    private static void captureInstance(Object candidate) {
        if (!isMtop(candidate) || mtopInstance != null) {
            return;
        }
        mtopInstance = candidate;
        Log.debug("[" + TAG + "] 已捕获宿主 Mtop 实例: " + candidate.getClass().getName());
    }

    private static Object resolveMtop() {
        Object cached = mtopInstance;
        if (isMtop(cached)) {
            return cached;
        }
        // 1) 模板 builder 里的 Mtop 引用
        captureInstance(findMtop(templateBuilder, 2, new int[]{60}));
        if (isMtop(mtopInstance)) {
            return mtopInstance;
        }
        Class<?> mc = classIfExists(CLS_MTOP);
        if (mc == null) {
            return null;
        }
        // 2) Mtop 的静态字段 / 静态 Map 里的单例
        for (Field f : allFields(mc)) {
            if (!Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object v = f.get(null);
                if (isMtop(v)) {
                    captureInstance(v);
                    return mtopInstance;
                }
                if (v instanceof Map) {
                    for (Object item : ((Map<?, ?>) v).values()) {
                        if (isMtop(item)) {
                            captureInstance(item);
                            return mtopInstance;
                        }
                    }
                }
            } catch (Throwable ignore) {
            }
        }
        // 3) Mtop.instance(...) 兜底: 按参数类型填充 id/context/字符串
        Context ctx = ApplicationHook.getContext();
        Class<?> idClazz = classIfExists(CLS_MTOP + "$Id");
        List<Object> pool = new ArrayList<>();
        if (idClazz != null) {
            for (Object id : enumValues(idClazz)) {
                pool.add(id);
            }
        }
        if (ctx != null) {
            pool.add(ctx);
        }
        pool.add(ISV_OPEN_APPKEY);
        for (Method m : allMethods(mc)) {
            if (!"instance".equals(m.getName())) {
                continue;
            }
            Object[] args = fillArgs(m.getParameterTypes(), pool);
            if (args == null) {
                continue;
            }
            Object v = invokeMethod(m, null, args);
            if (isMtop(v)) {
                captureInstance(v);
                return mtopInstance;
            }
        }
        return null;
    }

    /** 在对象(含 Map 值)里找 Mtop 实例, depth 限制递归层数, visit 限制扫描对象数。 */
    private static Object findMtop(Object obj, int depth, int[] visit) {
        if (obj == null || depth < 0 || visit[0] <= 0) {
            return null;
        }
        visit[0]--;
        if (isMtop(obj)) {
            return obj;
        }
        for (Field f : allFields(obj.getClass())) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            Class<?> t = f.getType();
            boolean interesting = CLS_MTOP.equals(t.getName())
                    || Map.class.isAssignableFrom(t)
                    || t.getName().startsWith("mtopsdk.");
            if (!interesting) {
                continue;
            }
            Object v = readField(f, obj);
            if (v == null) {
                continue;
            }
            if (isMtop(v)) {
                return v;
            }
            if (v instanceof Map) {
                for (Object item : ((Map<?, ?>) v).values()) {
                    if (isMtop(item)) {
                        return item;
                    }
                }
            }
            if (depth > 0 && t.getName().startsWith("mtopsdk.")) {
                Object r = findMtop(v, depth - 1, visit);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static boolean isMtop(Object obj) {
        return obj != null && CLS_MTOP.equals(obj.getClass().getName());
    }

    private static boolean isBuilder(Object obj) {
        return obj != null && obj.getClass().getName().startsWith(CLS_BUILDER);
    }

    // ═══════════════════════════ 构造请求 ═══════════════════════════

    private static Object newRequest(String api, String version, String data) {
        List<Class<?>> candidates = new ArrayList<>();
        if (templateRequestClazz != null) {
            candidates.add(templateRequestClazz);
        }
        Class<?> rc = classIfExists(CLS_REQUEST);
        if (rc != null && !candidates.contains(rc)) {
            candidates.add(rc);
        }
        for (Class<?> c : candidates) {
            Object req = instantiateRequest(c, api, version, data);
            if (req != null) {
                return req;
            }
        }
        return null;
    }

    private static Object instantiateRequest(Class<?> clazz, String api, String version, String data) {
        Object obj = null;
        try {
            obj = XHelpers.newInstance(clazz);
        } catch (Throwable ignore) {
        }
        if (obj == null) {
            // (apiName, version) 之类带参构造兜底
            Constructor<?>[] ctors = clazz.getDeclaredConstructors();
            Arrays.sort(ctors, new Comparator<Constructor<?>>() {
                @Override
                public int compare(Constructor<?> a, Constructor<?> b) {
                    return b.getParameterCount() - a.getParameterCount();
                }
            });
            for (Constructor<?> ctor : ctors) {
                Object[] args = fillArgs(ctor.getParameterTypes(), Arrays.asList(api, version, data));
                if (args == null) {
                    continue;
                }
                try {
                    ctor.setAccessible(true);
                    obj = ctor.newInstance(args);
                    break;
                } catch (Throwable ignore) {
                }
            }
        }
        if (obj == null) {
            return null;
        }
        if (writeValue(obj, "apiName", api) == null
                || writeValue(obj, "version", version) == null
                || writeValue(obj, "data", data) == null) {
            return null;
        }
        return obj;
    }

    private static Object newBuilder(Object mtop, Object request) {
        Context ctx = ApplicationHook.getContext();
        List<Object> pool = new ArrayList<>();
        pool.add(request);
        pool.add(mtop);
        if (!StringUtil.isEmpty(ttid)) {
            pool.add(ttid);
        }
        if (ctx != null) {
            pool.add(ctx);
        }
        // 1) mtop.build(request, ttid) 之类工厂方法
        for (Method m : allMethods(mtop.getClass())) {
            String n = m.getName();
            if (!"build".equals(n) && !"buildMtopBuilder".equals(n) && !"createBuilder".equals(n)) {
                continue;
            }
            Object[] args = fillArgs(m.getParameterTypes(), pool);
            if (args == null) {
                continue;
            }
            Object b = invokeMethod(m, mtop, args);
            if (isBuilder(b) && ensureMtopBound(b, mtop)) {
                return b;
            }
        }
        // 2) 直接 new MtopBuilder(...), 参数多的优先(通常带 Mtop/MtopRequest)
        Class<?> bc = classIfExists(CLS_BUILDER);
        if (bc != null) {
            Constructor<?>[] ctors = bc.getDeclaredConstructors();
            Arrays.sort(ctors, new Comparator<Constructor<?>>() {
                @Override
                public int compare(Constructor<?> a, Constructor<?> b) {
                    return b.getParameterCount() - a.getParameterCount();
                }
            });
            for (Constructor<?> ctor : ctors) {
                Object[] args = fillArgs(ctor.getParameterTypes(), pool);
                if (args == null) {
                    continue;
                }
                Object b = tryNew(ctor, args);
                if (isBuilder(b) && ensureMtopBound(b, mtop)) {
                    return b;
                }
            }
        }
        // 3) 复用宿主模板 builder: 只换请求对象(其余配置全是宿主现成的)
        if (templateBuilder != null) {
            for (String setter : new String[]{"setMtopRequest", "setRequest"}) {
                if (callByName(templateBuilder, setter, request)) {
                    return templateBuilder;
                }
            }
        }
        return null;
    }

    /** 保证 builder 上挂着 Mtop 实例(部分版本允许先 new 再 set)。 */
    private static boolean ensureMtopBound(Object builder, Object mtop) {
        if (findMtop(builder, 1, new int[]{30}) != null) {
            return true;
        }
        for (String setter : new String[]{"setMtop", "setMtopInstance"}) {
            if (callByName(builder, setter, mtop) && findMtop(builder, 1, new int[]{30}) != null) {
                return true;
            }
        }
        return writeValue(builder, "mtop", mtop) != null
                || writeValue(builder, "mMtop", mtop) != null;
    }

    // ═══════════════════════════ 配置与请求头 ═══════════════════════════

    /** 对齐宿主 JS 侧传下来的 MTOP 环境参数(自建 builder 的默认值不保证一致)。 */
    private static void applyConfig(Object builder, Object request, boolean needWua, List<String> notes) {
        mirror(builder, request, notes, "needWua", needWua);
        mirror(builder, request, notes, "needHttps", Boolean.TRUE);
        mirror(builder, request, notes, "usePost", Boolean.FALSE);
        mirror(builder, request, notes, "needEcodeSign", Boolean.FALSE);
        mirror(builder, request, notes, "needEcode", Boolean.FALSE);
        mirror(builder, request, notes, "needSession", Boolean.FALSE);
        mirror(builder, request, notes, "timeout", DEFAULT_TIMEOUT_MS);
        if (!StringUtil.isEmpty(ttid)) {
            mirror(builder, request, notes, "ttid", ttid);
        }
        mirror(builder, request, notes, "isvOpenAppkey", ISV_OPEN_APPKEY);
        if (!applyOriginalJson(builder, request, notes)) {
            notes.add("originaljson=无落点");
        }
    }

    /** 宿主 JS 侧传 ext_querys={"type":"originaljson"}: 少这个网关可能不回原始 JSON。 */
    private static boolean applyOriginalJson(Object builder, Object request, List<String> notes) {
        for (Object target : new Object[]{builder, request}) {
            if (target == null) {
                continue;
            }
            for (Method m : allMethods(target.getClass())) {
                String name = m.getName();
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 1 && Map.class.isAssignableFrom(pts[0]) && name.startsWith("set")) {
                    if (name.toLowerCase(Locale.ROOT).contains("extquer")) {
                        Map<String, String> map = new LinkedHashMap<>();
                        map.put("type", "originaljson");
                        if (call(m, target, map)) {
                            notes.add("originaljson=" + name);
                            return true;
                        }
                    }
                }
                if (pts.length == 2 && pts[0] == String.class && pts[1] == String.class) {
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.contains("extquery") || lower.contains("queryparam")) {
                        if (call(m, target, "type", "originaljson")) {
                            notes.add("originaljson=" + name);
                            return true;
                        }
                    }
                }
                if (pts.length == 1 && (pts[0] == String.class || CharSequence.class.isAssignableFrom(pts[0]))
                        && "setType".equals(name)) {
                    if (call(m, target, "originaljson")) {
                        notes.add("originaljson=setType");
                        return true;
                    }
                }
            }
            for (Field f : allFields(target.getClass())) {
                if (!Map.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                String fn = f.getName().toLowerCase(Locale.ROOT);
                if (!fn.contains("extquer")) {
                    continue;
                }
                Object v = readField(f, target);
                if (v instanceof Map) {
                    rawPut((Map) v, "type", "originaljson");
                    notes.add("originaljson=field:" + f.getName());
                    return true;
                }
            }
        }
        return false;
    }

    /** 把 alty_ext / alty_sso_token 等头塞进宿主 builder 的请求头容器。 */
    private static String applyHeaders(Object builder, Object request, Map<String, String> headers) {
        if (headers.isEmpty()) {
            return "headers=空";
        }
        String how = putHeaders(builder, headers);
        if (how == null) {
            how = putHeaders(request, headers);
        }
        if (how == null) {
            Log.record("芝麻狗MTOP⚠️未找到宿主请求头落点, token 头未注入(可能鉴权失败)");
            return "headers=未找到落点";
        }
        return "headers=" + how;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String putHeaders(Object target, Map<String, String> headers) {
        if (target == null) {
            return null;
        }
        Class<?> c = target.getClass();
        // 1) 名字里带 header 的单参 Map 方法
        for (Method m : allMethods(c)) {
            if (m.getParameterTypes().length != 1 || !Map.class.isAssignableFrom(m.getParameterTypes()[0])) {
                continue;
            }
            if (!m.getName().toLowerCase(Locale.ROOT).contains("header")) {
                continue;
            }
            if (call(m, target, headers)) {
                return "method:" + m.getName();
            }
        }
        // 2) (String, String) 的 addHeader/putHeader 之类
        for (Method m : allMethods(c)) {
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length != 2 || pts[0] != String.class || pts[1] != String.class) {
                continue;
            }
            if (!m.getName().toLowerCase(Locale.ROOT).contains("header")) {
                continue;
            }
            boolean any = false;
            for (Map.Entry<String, String> e : headers.entrySet()) {
                any |= call(m, target, e.getKey(), e.getValue());
            }
            if (any) {
                return "method:" + m.getName();
            }
        }
        // 3) Map 字段兜底: 名字像 header 的优先, 其次键里已有 alty/asac/wua 的
        Field best = null;
        int bestScore = -1;
        for (Field f : allFields(c)) {
            if (Modifier.isStatic(f.getModifiers()) || !Map.class.isAssignableFrom(f.getType())) {
                continue;
            }
            Object v = readField(f, target);
            if (!(v instanceof Map)) {
                continue;
            }
            int score = headerMapScore(f.getName(), (Map) v);
            if (score > bestScore) {
                bestScore = score;
                best = f;
            }
        }
        if (best != null && bestScore > 0) {
            Object v = readField(best, target);
            if (v instanceof Map) {
                ((Map) v).putAll(headers);
                return "field:" + best.getName();
            }
        }
        return null;
    }

    /** 头容器打分: 名字像 header +100, 键里已有 alty/asac/wua 等 +50。 */
    @SuppressWarnings("rawtypes")
    private static int headerMapScore(String fieldName, Map map) {
        int score = 0;
        if (fieldName.toLowerCase(Locale.ROOT).contains("header")) {
            score += 100;
        }
        try {
            for (Object key : map.keySet()) {
                String ks = String.valueOf(key).toLowerCase(Locale.ROOT);
                for (String hint : HEADER_KEY_HINTS) {
                    if (ks.contains(hint)) {
                        score += 50;
                        break;
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return score;
    }

    /** 从 builder 上读回宿主的请求头快照(用于 SDK 不自动注入安全头时的兜底重试)。 */
    @SuppressWarnings("rawtypes")
    private static Map<String, String> readHeaderMap(Object builder) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : new String[]{"getHeaders", "getHeaderMap", "getRequestHeaders"}) {
            Object v = tryInvoke(builder, name);
            if (v instanceof Map) {
                collectHeaders(out, (Map) v);
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        Field best = null;
        int bestScore = -1;
        for (Field f : allFields(builder.getClass())) {
            if (Modifier.isStatic(f.getModifiers()) || !Map.class.isAssignableFrom(f.getType())) {
                continue;
            }
            Object v = readField(f, builder);
            if (!(v instanceof Map)) {
                continue;
            }
            int score = headerMapScore(f.getName(), (Map) v);
            if (score > bestScore) {
                bestScore = score;
                best = f;
            }
        }
        if (best != null && bestScore > 0) {
            collectHeaders(out, (Map) readField(best, builder));
        }
        return out;
    }

    private static void collectHeaders(Map<String, String> out, Map<?, ?> src) {
        if (src == null) {
            return;
        }
        try {
            for (Map.Entry<?, ?> e : src.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        } catch (Throwable ignore) {
        }
    }

    /**
     * 兜底读取宿主请求头: 部分宿主版本把 JS 侧整包参数(apiName/apiVersion/dataText/headers/needWua)
     * 序列化进了 request.data, 真正的 headers 作为其中的 "headers" 字段(JSONObject 或 JSON 字符串)。
     */
    private static Map<String, String> headersFromRequestData(Object builder) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            Object req = findRequest(builder);
            Object data = req == null ? null : tryInvoke(req, "getData");
            if (data == null && req != null) {
                data = tryInvoke(req, "getDataText");
            }
            if (data == null) {
                data = tryInvoke(builder, "getData");
            }
            if (data == null) {
                return out;
            }
            String text = String.valueOf(data).trim();
            if (text.length() < 2 || text.charAt(0) != '{') {
                return out;
            }
            JSONObject obj = new JSONObject(text);
            Object h = obj.opt("headers");
            if (h instanceof JSONObject) {
                collectJsonHeaders(out, (JSONObject) h);
            } else if (h instanceof String && ((String) h).trim().startsWith("{")) {
                collectJsonHeaders(out, new JSONObject((String) h));
            }
        } catch (Throwable ignore) {
        }
        return out;
    }

    private static void collectJsonHeaders(Map<String, String> out, JSONObject src) {
        try {
            java.util.Iterator<String> it = src.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = src.optString(k, "");
                if (!k.isEmpty() && !v.isEmpty()) {
                    out.put(k, v);
                }
            }
        } catch (Throwable ignore) {
        }
    }

    /** 只保留安全/会话类头(alty_*、asac、wua、umt、x-sgext、sg*), 避免把宿主其它头覆盖进重试请求。 */
    private static Map<String, String> securityHeaders(Map<String, String> src) {
        Map<String, String> out = new LinkedHashMap<>();
        if (src == null || src.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, String> e : src.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT);
            if (k.startsWith("sg") || k.contains("alty") || k.contains("asac") || k.contains("wua")
                    || k.contains("umt") || k.contains("x-sgext")) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static Map<String, String> observedHeadersFor(String apiName) {
        Map<String, String> exact = OBSERVED_HEADERS.get(apiName);
        if (exact != null && !exact.isEmpty()) {
            return exact;
        }
        return lastObservedHeaders;
    }

    // ═══════════════════════════ 发送与解析 ═══════════════════════════

    private static Result doSend(Object builder, String apiName, String note) {
        Object response;
        try {
            response = XHelpers.callMethod(builder, "syncRequest");
        } catch (Throwable t) {
            return fail(apiName, "syncRequest 调用失败: " + t, note);
        }
        return parseResponse(apiName, response, note);
    }

    private static Result parseResponse(String apiName, Object response, String note) {
        if (response == null) {
            return fail(apiName, "响应为 null(宿主 mtopsdk 拒绝或网络异常)", note);
        }
        String code = asString(tryInvoke(response, "getRetCode"));
        String msg = asString(tryInvoke(response, "getRetMsg"));
        String api = asString(tryInvoke(response, "getApi"));
        if (StringUtil.isEmpty(api)) {
            api = apiName;
        }
        String[] retArr = asStringArray(tryInvoke(response, "getRet"));
        JSONObject data = asJsonObject(tryInvoke(response, "getDataJsonObject"));
        if (data == null) {
            data = asJsonObject(tryInvoke(response, "getData"));
        }
        if (data == null) {
            data = asJsonObject(readFieldByName(response, "data"));
        }
        if (retArr == null) {
            retArr = asStringArray(readFieldByName(response, "ret"));
        }
        if (StringUtil.isEmpty(code)) {
            code = asString(readFieldByName(response, "retCode"));
        }
        if (StringUtil.isEmpty(msg)) {
            msg = asString(readFieldByName(response, "retMsg"));
        }
        String retText = retArr != null ? joinArray(retArr) : "";
        boolean ok = false;
        if (retArr != null) {
            for (String s : retArr) {
                if (s != null && s.toUpperCase(Locale.ROOT).startsWith("SUCCESS")) {
                    ok = true;
                    break;
                }
            }
        }
        if (!ok && StringUtil.isEmpty(code) && StringUtil.isEmpty(msg) && retText.isEmpty() && data == null) {
            return fail(api, "响应不可解析(未取到 ret/retCode/data)", note + "|" + probe());
        }
        String reason = ok ? "" : (StringUtil.isEmpty(retText)
                ? (StringUtil.isEmpty(code) ? "接口返回失败" : code + " " + msg)
                : retText);
        Log.debug("[" + TAG + "] ← " + api + " " + (ok ? "OK" : "FAIL") + " " + mask(reason, 300));
        return new Result(ok, api, code, msg, retText, data, reason, note);
    }

    private static Result fail(String api, String reason, String note) {
        Log.record("芝麻狗MTOP⚠️ " + api + " → " + reason);
        return new Result(false, api, "", "", "", null, reason, note);
    }

    // ═══════════════════════════ 反射工具 ═══════════════════════════

    /** 先试 setXxx(...) 再退化为同名字段直写, 命中即返回落点描述。 */
    private static String mirror(Object builder, Object request, List<String> notes, String name, Object value) {
        String how = writeValue(builder, name, value);
        if (how == null) {
            how = writeValue(request, name, value);
        }
        notes.add(name + "=" + (how == null ? "无落点" : how));
        return how;
    }

    private static String writeValue(Object target, String name, Object value) {
        if (target == null || name == null || name.isEmpty()) {
            return null;
        }
        String setter = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (Method m : allMethods(target.getClass())) {
            if (!setter.equals(m.getName()) || m.getParameterTypes().length != 1) {
                continue;
            }
            Class<?> pt = m.getParameterTypes()[0];
            if (!isCompatible(pt, value)) {
                continue;
            }
            if (call(m, target, coerce(pt, value))) {
                return "setter";
            }
        }
        for (Field f : allFields(target.getClass())) {
            if (!f.getName().equalsIgnoreCase(name) || Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (!isCompatible(f.getType(), value)) {
                continue;
            }
            try {
                f.setAccessible(true);
                f.set(target, coerce(f.getType(), value));
                return "field";
            } catch (Throwable ignore) {
            }
        }
        return null;
    }

    private static boolean isCompatible(Class<?> type, Object value) {
        if (value == null) {
            return !type.isPrimitive();
        }
        if (type.isPrimitive()) {
            if (value instanceof Boolean) {
                return type == boolean.class;
            }
            if (value instanceof Integer) {
                return type == int.class || type == long.class || type == short.class
                        || type == byte.class || type == float.class || type == double.class;
            }
            return false;
        }
        return type.isInstance(value) || CharSequence.class.isAssignableFrom(type);
    }

    private static Object coerce(Class<?> type, Object value) {
        if (value == null || !type.isPrimitive() || !(value instanceof Number)) {
            if (value != null && type == String.class && !(value instanceof String)) {
                return String.valueOf(value);
            }
            return value;
        }
        Number n = (Number) value;
        if (type == int.class) {
            return n.intValue();
        }
        if (type == long.class) {
            return n.longValue();
        }
        if (type == short.class) {
            return n.shortValue();
        }
        if (type == byte.class) {
            return n.byteValue();
        }
        if (type == float.class) {
            return n.floatValue();
        }
        if (type == double.class) {
            return n.doubleValue();
        }
        return value;
    }

    /** 按参数类型从候选池里逐个取赋值对象; 任一参数填不上就返回 null(放弃这个重载)。 */
    private static Object[] fillArgs(Class<?>[] types, List<?> pool) {
        if (types == null) {
            return null;
        }
        Object[] args = new Object[types.length];
        boolean[] used = new boolean[pool.size()];
        for (int i = 0; i < types.length; i++) {
            boolean found = false;
            for (int j = 0; j < pool.size(); j++) {
                if (used[j]) {
                    continue;
                }
                Object candidate = pool.get(j);
                if (isCompatible(types[i], candidate)) {
                    args[i] = coerce(types[i], candidate);
                    used[j] = true;
                    found = true;
                    break;
                }
            }
            if (!found) {
                return null;
            }
        }
        return args;
    }

    private static List<Method> allMethods(Class<?> clazz) {
        List<Method> list = new ArrayList<>();
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            try {
                for (Method m : c.getDeclaredMethods()) {
                    list.add(m);
                }
            } catch (Throwable ignore) {
            }
            c = c.getSuperclass();
        }
        return list;
    }

    private static List<Field> allFields(Class<?> clazz) {
        List<Field> list = new ArrayList<>();
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            try {
                for (Field f : c.getDeclaredFields()) {
                    list.add(f);
                }
            } catch (Throwable ignore) {
            }
            c = c.getSuperclass();
        }
        return list;
    }

    private static Object tryInvoke(Object target, String name) {
        return tryInvoke(target, name, new Object[0]);
    }

    private static Object tryInvoke(Object target, String name, Object... args) {
        if (target == null) {
            return null;
        }
        for (Method m : allMethods(target.getClass())) {
            if (!name.equals(m.getName()) || m.getParameterTypes().length != args.length) {
                continue;
            }
            try {
                m.setAccessible(true);
                return m.invoke(target, args);
            } catch (Throwable ignore) {
            }
        }
        return null;
    }

    private static boolean call(Method m, Object target, Object... args) {
        try {
            m.setAccessible(true);
            m.invoke(target, args);
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** 调用一个已定位到的 Method(static 时 target 传 null), 失败返回 null。 */
    private static Object invokeMethod(Method m, Object target, Object[] args) {
        if (m == null) {
            return null;
        }
        try {
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static boolean callByName(Object target, String name, Object arg) {
        if (target == null) {
            return false;
        }
        for (Method m : allMethods(target.getClass())) {
            if (!name.equals(m.getName()) || m.getParameterTypes().length != 1) {
                continue;
            }
            if (!isCompatible(m.getParameterTypes()[0], arg)) {
                continue;
            }
            if (call(m, target, coerce(m.getParameterTypes()[0], arg))) {
                return true;
            }
        }
        return false;
    }

    private static Object tryNew(Constructor<?> ctor, Object[] args) {
        try {
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static Object readField(Field f, Object target) {
        try {
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static Object readFieldByName(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Field f : allFields(target.getClass())) {
            if (f.getName().equalsIgnoreCase(name)) {
                Object v = readField(f, target);
                if (v != null) {
                    return v;
                }
            }
        }
        return null;
    }

    private static Class<?> classIfExists(String name) {
        ClassLoader cl = ApplicationHook.getClassLoader();
        if (cl == null) {
            return null;
        }
        try {
            return XHelpers.findClassIfExists(name, cl);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static List<Object> enumValues(Class<?> enumClazz) {
        List<Object> list = new ArrayList<>();
        try {
            Object[] values = enumClazz.getEnumConstants();
            if (values != null) {
                for (Object v : values) {
                    list.add(v);
                }
            }
        } catch (Throwable ignore) {
        }
        return list;
    }

    private static String readApi(Object builder) {
        Object req = findRequest(builder);
        String api = asString(tryInvoke(req, "getApiName"));
        if (StringUtil.isEmpty(api)) {
            api = asString(readFieldByName(req, "apiName"));
        }
        return api == null ? "" : api;
    }

    private static Object findRequest(Object builder) {
        Object req = tryInvoke(builder, "getMtopRequest");
        if (req == null) {
            req = tryInvoke(builder, "getRequest");
        }
        if (req == null) {
            req = readFieldByName(builder, "mtopRequest");
        }
        if (req == null) {
            req = readFieldByName(builder, "request");
        }
        return req;
    }

    // ═══════════════════════════ 文本工具 ═══════════════════════════

    @SuppressWarnings("rawtypes")
    private static void rawPut(Map map, Object key, Object value) {
        try {
            map.put(key, value);
        } catch (Throwable ignore) {
        }
    }

    private static String asString(Object obj) {
        return obj == null ? "" : String.valueOf(obj);
    }

    private static String[] asStringArray(Object obj) {
        if (obj instanceof String[]) {
            return (String[]) obj;
        }
        if (obj instanceof Object[]) {
            Object[] arr = (Object[]) obj;
            String[] out = new String[arr.length];
            for (int i = 0; i < arr.length; i++) {
                out[i] = arr[i] == null ? "" : String.valueOf(arr[i]);
            }
            return out;
        }
        if (obj instanceof CharSequence) {
            return new String[]{String.valueOf(obj)};
        }
        return null;
    }

    private static JSONObject asJsonObject(Object obj) {
        if (obj instanceof JSONObject) {
            return (JSONObject) obj;
        }
        if (obj instanceof byte[]) {
            try {
                return asJsonObject(new String((byte[]) obj, java.nio.charset.StandardCharsets.UTF_8));
            } catch (Throwable ignore) {
            }
        }
        if (obj instanceof CharSequence) {
            String s = String.valueOf(obj).trim();
            if (s.startsWith("{")) {
                try {
                    return new JSONObject(s);
                } catch (Throwable ignore) {
                }
            }
        }
        return null;
    }

    private static String joinArray(String[] arr) {
        StringBuilder sb = new StringBuilder();
        for (String s : arr) {
            if (s == null || s.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(";");
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private static String joinNotes(List<String> notes, String headerNote) {
        return "cfg[" + join(notes) + "] " + headerNote;
    }

    /** 日志脱敏: 只留前 4 位与长度, 避免把 token 完整写进运行日志。 */
    private static String mask(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.length() > max ? s.substring(0, max) + "..." : s;
        return t.replaceAll("(at[A-Za-z0-9]{4})[A-Za-z0-9]{6,}", "$1***")
                .replaceAll("(\"(?:accessToken|access_token|sso_token|auth_code|token)\"\\s*:\\s*\"[A-Za-z0-9]{4})[A-Za-z0-9]+", "$1***");
    }

    private static String maskMap(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append("=").append(mask(e.getValue(), 12));
        }
        return sb.append("}").toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static void appendMembers(StringBuilder sb, String label, Class<?> clazz) {
        try {
            sb.append("\n").append(label).append(" 构造器: ");
            for (Constructor<?> c : clazz.getDeclaredConstructors()) {
                sb.append("(").append(paramTypes(c.getParameterTypes())).append(") ");
            }
            sb.append("\n").append(label).append(" 方法: ");
            int count = 0;
            for (Method m : clazz.getDeclaredMethods()) {
                String n = m.getName();
                if (n.startsWith("access$") || n.startsWith("lambda$")) {
                    continue;
                }
                sb.append(n).append("(").append(paramTypes(m.getParameterTypes())).append(") ");
                if (++count >= 40) {
                    sb.append("...");
                    break;
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private static String paramTypes(Class<?>[] types) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(types[i] == null ? "?" : types[i].getSimpleName());
        }
        return sb.toString();
    }

    // ═══════════════════════════ 结果对象 ═══════════════════════════

    /** 一次 MTOP 调用的结果(业务 + 排障信息)。 */
    public static final class Result {

        public final boolean ok;
        public final String api;
        public final String retCode;
        public final String retMsg;
        public final String retText;
        public final JSONObject data;
        public final String failReason;
        public final String note;

        Result(boolean ok, String api, String retCode, String retMsg, String retText, JSONObject data,
               String failReason, String note) {
            this.ok = ok;
            this.api = api;
            this.retCode = retCode == null ? "" : retCode;
            this.retMsg = retMsg == null ? "" : retMsg;
            this.retText = retText == null ? "" : retText;
            this.data = data;
            this.failReason = failReason == null ? "" : failReason;
            this.note = note == null ? "" : note;
        }

        /** 业务数据体: 兼容 data 为字符串(未走 originaljson)时再解一层。 */
        public JSONObject body() {
            if (data == null) {
                return null;
            }
            Object inner = data.opt("data");
            if (inner instanceof JSONObject) {
                return (JSONObject) inner;
            }
            if (inner instanceof String) {
                String s = ((String) inner).trim();
                if (s.startsWith("{")) {
                    try {
                        return new JSONObject(s);
                    } catch (Throwable ignore) {
                    }
                }
            }
            return data;
        }

        /** 今日前进次数已达上限(要切城市记录的信号)。 */
        public boolean isDailyLimit() {
            return retText.contains("DAILY_LIMIT") || failReason.contains("DAILY_LIMIT");
        }

        /** 签名/鉴权类失败(可尝试用宿主真实请求头重发)。 */
        public boolean isSecurityFail() {
            String s = (retText + " " + retCode + " " + failReason).toUpperCase(Locale.ROOT);
            return s.contains("ILLEGAL") || s.contains("SIGN") || s.contains("WUA")
                    || s.contains("SECURITY") || s.contains("AUTH_FAILED") || s.contains("NOT_LOGIN")
                    || s.contains("UNAUTHORIZED") || s.contains("FAIL_SYS_ILLEGAL_ACCESS")
                    || s.contains("SESSION_EXPIRED");
        }

        /** 活动积分/余额不足(前进被拒, 再刷也无意义)。 */
        public boolean isPointsNotEnough() {
            String s = (retText + " " + retCode + " " + failReason).toUpperCase(Locale.ROOT);
            return s.contains("INSUFFICIENT") || s.contains("NOT_ENOUGH") || s.contains("NOTENOUGH")
                    || s.contains("POINTS_LIMIT") || s.contains("BALANCE_LIMIT")
                    || s.contains("积分不足") || s.contains("余额不足");
        }

        /** 鉴权失效(需要刷 token 后重试)。 */
        public boolean isAuthExpired() {
            String s = (retText + " " + retCode + " " + failReason).toUpperCase(Locale.ROOT);
            return s.contains("TOKEN") || s.contains("NOT_LOGIN") || s.contains("SESSION_EXPIRED")
                    || s.contains("AUTH_EXPIRED") || s.contains("INVALID_SESSION");
        }

        public String brief() {
            if (ok) {
                return "OK";
            }
            return StringUtil.isEmpty(failReason) ? "失败" : failReason;
        }

        @Override
        public String toString() {
            return api + " " + brief();
        }
    }
}
