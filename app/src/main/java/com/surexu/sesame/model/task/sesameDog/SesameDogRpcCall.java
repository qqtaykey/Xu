package com.surexu.sesame.model.task.sesameDog;

import com.surexu.sesame.hook.MtopSender;
import com.surexu.sesame.hook.SesameDogAuth;
import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.StringUtil;

import org.json.JSONObject;

/**
 * 芝麻粒游乐记(芝麻狗) MTOP 接口封装。
 *
 * <p>参数模板全部对齐真机抓包(见 temp/.tool-results/dog_log_report.txt):
 * <ul>
 *   <li>index / forward / listparticipateplayingrecord 的请求头必须带
 *       {@code alty_ext=version=2} 与 {@code alty_sso_token=<sso token>};</li>
 *   <li>forward 另需 {@code needWua=true}(wua/asac 由宿主安全 SDK 生成);</li>
 *   <li>接口 data 里的 {@code accessToken} 填 sso token;</li>
 *   <li>接口版本统一 1.0(授权类接口 2.0)。</li>
 * </ul>
 */
public class SesameDogRpcCall {

    private SesameDogRpcCall() {
    }

    private static final String TAG = "SesameDogRpcCall";
    private static final String VERSION = "1.0";

    public static final String API_PAGEROUTE = "mtop.alisports.play.common.pageroute";
    public static final String API_INDEX = "mtop.alisports.play.sesamedog.index";
    public static final String API_FORWARD = "mtop.alisports.play.sesamedog.forward";
    public static final String API_LIST_RECORD = "mtop.alisports.play.sesamedog.listparticipateplayingrecord";
    public static final String API_CHANGE_RECORD = "mtop.alisports.play.sesamedog.changecurplayingrecord";
    public static final String API_QUERY_AWARD = "mtop.alisports.play.sesamedog.queryaward";

    /** 需要 token 的调用。 */
    public interface TokenCall {
        MtopSender.Result run(String token);
    }

    // ═══════════════════════════ 接口 ═══════════════════════════

    /** 进入活动页(判定 routeRedirectType, 兼作活动可用性探测)。 */
    public static MtopSender.Result pageroute() {
        try {
            JSONObject extra = new JSONObject();
            extra.put("chn", "");
            extra.put("version", VERSION);
            JSONObject data = new JSONObject();
            data.put("extraData", extra.toString());
            data.put("visitSource", "");
            // 抓包里 pageroute 同样带 alty_ext/alty_sso_token; 拿不到 token 也不阻断(该接口本身不校验)
            String token = SesameDogAuth.peekToken();
            return MtopSender.send(API_PAGEROUTE, VERSION, data.toString(), false,
                    StringUtil.isEmpty(token) ? null : authHeaders(token));
        } catch (Throwable t) {
            Log.err(TAG, "pageroute err:", t);
            return null;
        }
    }

    /** 活动首页: 拿到当前城市/站点/进度, 以及本次可前进的次数依据。 */
    public static MtopSender.Result index() {
        return withToken(new TokenCall() {
            @Override
            public MtopSender.Result run(String token) {
                try {
                    JSONObject extend = new JSONObject();
                    extend.put("exposureNum", 0);
                    extend.put("dogVersion", "3.0");
                    JSONObject data = new JSONObject();
                    data.put("accessToken", token);
                    data.put("extendData", extend.toString());
                    return MtopSender.send(API_INDEX, VERSION, data.toString(), false, authHeaders(token));
                } catch (Throwable t) {
                    Log.err(TAG, "index err:", t);
                    return null;
                }
            }
        });
    }

    /** 前进一次(消耗 5 积分 / +5 进度); 城市记录满额时返回 SESAME_DOG_DAILY_LIMIT。 */
    public static MtopSender.Result forward(final String playingKey) {
        return withToken(new TokenCall() {
            @Override
            public MtopSender.Result run(String token) {
                try {
                    JSONObject extend = new JSONObject();
                    extend.put("chn", "");
                    JSONObject data = new JSONObject();
                    data.put("accessToken", token);
                    data.put("extendData", extend.toString());
                    data.put("playingKey", playingKey == null ? "" : playingKey);
                    return MtopSender.send(API_FORWARD, VERSION, data.toString(), true, authHeaders(token));
                } catch (Throwable t) {
                    Log.err(TAG, "forward err:", t);
                    return null;
                }
            }
        });
    }

    /** 我参与过的游玩记录(每个记录独立计数 todayForwardCount)。 */
    public static MtopSender.Result listRecords() {
        return withToken(new TokenCall() {
            @Override
            public MtopSender.Result run(String token) {
                try {
                    return MtopSender.send(API_LIST_RECORD, VERSION, "{}", false, authHeaders(token));
                } catch (Throwable t) {
                    Log.err(TAG, "listRecords err:", t);
                    return null;
                }
            }
        });
    }

    /** 切换当前游玩记录(城市/站点), 参数名是 playingRecordId。 */
    public static MtopSender.Result changeRecord(final String playingRecordId) {
        return withToken(new TokenCall() {
            @Override
            public MtopSender.Result run(String token) {
                try {
                    JSONObject data = new JSONObject();
                    data.put("playingRecordId", playingRecordId == null ? "" : playingRecordId);
                    return MtopSender.send(API_CHANGE_RECORD, VERSION, data.toString(), false, authHeaders(token));
                } catch (Throwable t) {
                    Log.err(TAG, "changeRecord err:", t);
                    return null;
                }
            }
        });
    }

    /** 查询可领奖的城市(顺带刷新城市列表)。 */
    public static MtopSender.Result queryAward() {
        return withToken(new TokenCall() {
            @Override
            public MtopSender.Result run(String token) {
                try {
                    JSONObject extend = new JSONObject();
                    extend.put("channelSource", "multiPlaying");
                    JSONObject data = new JSONObject();
                    data.put("extendData", extend.toString());
                    data.put("pageSize", 10);
                    return MtopSender.send(API_QUERY_AWARD, VERSION, data.toString(), false, authHeaders(token));
                } catch (Throwable t) {
                    Log.err(TAG, "queryAward err:", t);
                    return null;
                }
            }
        });
    }

    // ═══════════════════════════ 公共 ═══════════════════════════

    /** 芝麻狗接口统一请求头: alty_ext + alty_sso_token。 */
    public static java.util.Map<String, String> authHeaders(String token) {
        return SesameDogAuth.ssoHeaders(token);
    }

    /**
     * 取 token 调接口; 若是 token 失效类失败, 作废缓存后强刷一次再试。
     * 返回 null 表示无法执行(没有任何可用 token)。
     */
    private static MtopSender.Result withToken(TokenCall call) {
        MtopSender.Result last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = SesameDogAuth.ensureToken(attempt > 0);
            if (StringUtil.isEmpty(token)) {
                return last;
            }
            last = call.run(token);
            if (last == null || last.ok || !last.isAuthExpired()) {
                return last;
            }
            Log.record("芝麻狗: 接口返回 " + last.brief() + ", 作废 token 后重试一次");
            SesameDogAuth.invalidate();
        }
        return last;
    }
}
