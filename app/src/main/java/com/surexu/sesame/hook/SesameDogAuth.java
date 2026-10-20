package com.surexu.sesame.hook;

import android.content.Context;
import android.content.SharedPreferences;

import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.StringUtil;
import com.surexu.sesame.util.idMap.UserIdMap;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 芝麻狗(alisp/alisports 体系)授权: 直接复用宿主会话换 alisports SSO token。
 *
 * <p>鉴权链路(抓包实测):
 * <ol>
 *   <li>{@code mtop.alisports.messi.user.auth.query} (forceRefresh=true) → 返回
 *       {@code ssoToken} / {@code accessToken} / {@code aliuid}, 无需任何授权码, 只要宿主支付宝/
 *       淘宝会话在线即可, 这是「直接拿支付宝授权」的主路径;</li>
 *   <li>兜底: {@code mtop.alisports.passport.account.auth.alipaygrant} 用 auth_code 换
 *       {@code sso_token}(宿主版本支持 auth_code 时才走)。</li>
 * </ol>
 *
 * <p>注意: 芝麻狗接口 data 里的 {@code accessToken} 字段填的就是 **sso token**;
 * 请求头另需 {@code alty_ext=version=2} 与 {@code alty_sso_token=<sso token>}。
 * token 有效期 7 天(expires_in=604800), 这里落盘缓存并按过期时间自动刷新。
 */
public final class SesameDogAuth {

    private SesameDogAuth() {
    }

    private static final String TAG = "SesameDogAuth";

    /** 宿主会话直接换 alisports SSO token */
    public static final String API_AUTH_QUERY = "mtop.alisports.messi.user.auth.query";
    /** auth_code 兜底换取(宿主版本可用时) */
    public static final String API_ALIPAY_GRANT = "mtop.alisports.passport.account.auth.alipaygrant";

    /** 芝麻粒游乐记(芝麻狗)小程序 appId */
    public static final String APP_ID = "2018073060792690";
    private static final String VERSION = "2.0";
    private static final String ALISP_APP_KEY = "alisp500055";
    private static final String SCOPE = "auth_user,auth_stepcounter,ant_zhimali_open";
    /** token 有效期 7 天, 提前 30 分钟视为过期 */
    private static final long EXPIRE_SECONDS = 604800L;
    private static final long EXPIRE_MARGIN_MS = 30L * 60L * 1000L;

    private static final String SP_NAME = "sesame_dog_auth";

    /** 内存缓存: token 与到期时间, 避免同一个任务里反复读盘 */
    private static volatile String cachedToken = "";
    private static volatile long cachedExpireAt = 0L;
    private static volatile String cachedUserScope = "";
    /** 最近一次拿到的 aliuid / 昵称, 只用于日志 */
    private static volatile String lastAliuid = "";
    private static volatile String lastNick = "";

    /**
     * 取一个可用的 SSO token(空串表示失败)。
     *
     * @param force true 表示忽略缓存强制刷新
     */
    public static String ensureToken(boolean force) {
        String scope = userScope();
        if (!force) {
            String cached = loadCachedToken(scope);
            if (!StringUtil.isEmpty(cached)) {
                return cached;
            }
        }
        String token = refresh();
        if (!StringUtil.isEmpty(token)) {
            saveToken(scope, token);
        }
        return token;
    }

    /** 只读看缓存里有没有可用 token(不触发刷新, 供请求头尽力而为的场景用)。 */
    public static String peekToken() {
        return loadCachedToken(userScope());
    }

    /** 作废缓存(接口报 token 失效时调用一次)。 */
    public static void invalidate() {
        cachedToken = "";
        cachedExpireAt = 0L;
        try {
            SharedPreferences sp = prefs();
            if (sp != null) {
                sp.edit().remove(tokenKey(userScope())).remove(expireKey(userScope())).apply();
            }
        } catch (Throwable ignore) {
        }
    }

    /** 芝麻狗接口请求头: alty_ext + alty_sso_token(抓包实测必需)。 */
    public static Map<String, String> ssoHeaders(String token) {
        Map<String, String> headers = new HashMap<>();
        headers.put("alty_ext", "version=2");
        if (!StringUtil.isEmpty(token)) {
            headers.put("alty_sso_token", token);
        }
        return headers;
    }

    public static String stateText() {
        String scope = userScope();
        String token = loadCachedToken(scope);
        return "授权[" + (StringUtil.isEmpty(token) ? "无可用token" : "已缓存token(剩余"
                + Math.max(0L, (cachedExpireAt - System.currentTimeMillis()) / 60000L) + "分钟)")
                + ", uid=" + scope
                + (StringUtil.isEmpty(lastAliuid) ? "" : ", aliuid=" + lastAliuid)
                + "]";
    }

    // ═══════════════════════════ 主链路 ═══════════════════════════

    private static String refresh() {
        // 1) 主路径: 宿主会话直接换 alisports SSO token(不需要授权码)
        String token = queryHostSsoToken(true);
        if (!StringUtil.isEmpty(token)) {
            Log.record("芝麻狗授权: 已复用宿主会话拿到 alisports token(7天有效)");
            return token;
        }
        // 2) 兜底: auth_code 换 token(部分宿主版本不提供授权码, 失败不影响主路径)
        String authCode = null;
        try {
            authCode = AuthCodeHelper.getAuthCode(APP_ID);
        } catch (Throwable t) {
            Log.err(TAG, "getAuthCode err:", t);
        }
        if (!StringUtil.isEmpty(authCode)) {
            token = grantByAuthCode(authCode);
            if (!StringUtil.isEmpty(token)) {
                Log.record("芝麻狗授权: 已用 auth_code 换取 alisports token");
                return token;
            }
        }
        Log.record("芝麻狗授权⚠️未取到 alisports token, 请确认支付宝处于登录态后在活动页打开过芝麻狗");
        return "";
    }

    /** 宿主会话直接换 SSO token: mtop.alisports.messi.user.auth.query。 */
    private static String queryHostSsoToken(boolean forceRefresh) {
        try {
            JSONObject data = new JSONObject();
            data.put("forceRefresh", forceRefresh);
            MtopSender.Result r = MtopSender.send(API_AUTH_QUERY, VERSION, data.toString(), false, null);
            if (!r.ok) {
                Log.record("芝麻狗授权⚠️宿主会话换取 token 失败: " + r.brief());
                return "";
            }
            JSONObject body = r.body();
            if (body == null) {
                Log.record("芝麻狗授权⚠️宿主会话换取 token 返回体为空");
                return "";
            }
            String token = body.optString("ssoToken");
            if (StringUtil.isEmpty(token)) {
                token = body.optString("accessToken");
            }
            lastAliuid = body.optString("aliuid");
            lastNick = body.optString("nick");
            if (StringUtil.isEmpty(token)) {
                Log.record("芝麻狗授权⚠️宿主会话返回里没有 ssoToken");
            }
            return token;
        } catch (Throwable t) {
            Log.err(TAG, "queryHostSsoToken err:", t);
            return "";
        }
    }

    /** auth_code → sso_token(兜底路径)。 */
    private static String grantByAuthCode(String authCode) {
        try {
            JSONObject data = new JSONObject();
            data.put("alisp_app_key", ALISP_APP_KEY);
            data.put("appid", APP_ID);
            data.put("auth_code", authCode);
            data.put("mode", 1);
            data.put("scope", SCOPE);
            data.put("sub_appkey", "");
            MtopSender.Result r = MtopSender.send(API_ALIPAY_GRANT, VERSION, data.toString(), false, null);
            if (!r.ok) {
                Log.record("芝麻狗授权⚠️auth_code 换取 token 失败: " + r.brief());
                return "";
            }
            JSONObject body = r.body();
            if (body == null) {
                return "";
            }
            // 抓包实测: 真正的 token 在 data.alispData 里(alispCode=200 为成功)
            JSONObject grant = body.optJSONObject("alispData");
            if (grant == null) {
                grant = body;
            } else {
                int alispCode = body.optInt("alispCode", 200);
                if (alispCode != 200) {
                    Log.record("芝麻狗授权⚠️auth_code 换取失败, alispCode=" + alispCode);
                }
            }
            String token = grant.optString("sso_token");
            if (StringUtil.isEmpty(token)) {
                token = grant.optString("access_token");
            }
            if (StringUtil.isEmpty(lastAliuid)) {
                lastAliuid = grant.optString("aliuid");
            }
            if (StringUtil.isEmpty(lastNick)) {
                lastNick = grant.optString("nick");
            }
            return token;
        } catch (Throwable t) {
            Log.err(TAG, "grantByAuthCode err:", t);
            return "";
        }
    }

    // ═══════════════════════════ 缓存 ═══════════════════════════

    private static String loadCachedToken(String scope) {
        long now = System.currentTimeMillis();
        if (!StringUtil.isEmpty(cachedToken) && scope.equals(cachedUserScope) && cachedExpireAt > now) {
            return cachedToken;
        }
        try {
            SharedPreferences sp = prefs();
            if (sp == null) {
                return "";
            }
            long expireAt = sp.getLong(expireKey(scope), 0L);
            String token = sp.getString(tokenKey(scope), "");
            if (StringUtil.isEmpty(token) || expireAt <= now) {
                return "";
            }
            cachedToken = token;
            cachedExpireAt = expireAt;
            cachedUserScope = scope;
            return token;
        } catch (Throwable t) {
            Log.err(TAG, "loadCachedToken err:", t);
            return "";
        }
    }

    private static void saveToken(String scope, String token) {
        long expireAt = System.currentTimeMillis() + EXPIRE_SECONDS * 1000L - EXPIRE_MARGIN_MS;
        cachedToken = token;
        cachedExpireAt = expireAt;
        cachedUserScope = scope;
        try {
            SharedPreferences sp = prefs();
            if (sp != null) {
                sp.edit().putString(tokenKey(scope), token).putLong(expireKey(scope), expireAt).apply();
            }
        } catch (Throwable t) {
            Log.err(TAG, "saveToken err:", t);
        }
    }

    private static SharedPreferences prefs() {
        Context ctx = ApplicationHook.getContext();
        if (ctx == null) {
            return null;
        }
        return ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    private static String userScope() {
        String uid = null;
        try {
            uid = UserIdMap.getCurrentUid();
        } catch (Throwable ignore) {
        }
        return StringUtil.isEmpty(uid) ? "default" : uid;
    }

    private static String tokenKey(String scope) {
        return "sso_token_" + scope;
    }

    private static String expireKey(String scope) {
        return "sso_expire_" + scope;
    }
}
