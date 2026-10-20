package com.surexu.sesame.model.task.sesameSports;

import com.surexu.sesame.hook.MtopSender;
import com.surexu.sesame.hook.SesameDogAuth;
import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.StringUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 体育游乐记(alisports「dog」活动, appId=2018073060792690) MTOP 接口封装。
 *
 * <p>参数模板全部对齐真机抓包(见 temp/.sports 报告):
 * <ul>
 *   <li>接口前缀是 {@code mtop.alisports.play.dog.common.*}(上一轮芝麻粒游乐记是
 *       {@code mtop.alisports.play.sesamedog.*}, 两者不同, 但授权方式一致);</li>
 *   <li>请求头必须带 {@code alty_ext=version=2} 与 {@code alty_sso_token=<sso token>};</li>
 *   <li>{@code forward} / {@code refreshPoints} 需要 {@code needWua=true}(wua/asac 由宿主安全 SDK 生成);</li>
 *   <li>抓包实测这些接口的 data 里**没有** accessToken 字段, 鉴权只靠请求头——不要照搬芝麻狗;</li>
 *   <li>接口版本统一 1.0(授权类接口 2.0)。</li>
 * </ul>
 */
public class SesameSportsRpcCall {

    private SesameSportsRpcCall() {
    }

    private static final String TAG = "SesameSportsRpcCall";
    private static final String VERSION = "1.0";

    /** 抓包实测渠道号(请求 extendData.chn)。 */
    public static final String CHN = "alipay.zfbsy.gg";
    /** 抓包实测活动任务中心 id(getTaskModuleInfo 参数)。 */
    public static final String TASK_ACTIVITY_ID = "13278";

    public static final String API_PAGEROUTE = "mtop.alisports.play.common.pageroute";
    public static final String API_PLAY_STATE = "mtop.alisports.play.dog.common.obtainPlayState";
    public static final String API_INDEX = "mtop.alisports.play.dog.common.index";
    public static final String API_FORWARD = "mtop.alisports.play.dog.common.forward";
    public static final String API_REFRESH_POINTS = "mtop.alisports.play.dog.common.refreshPoints";
    public static final String API_LIST_RECORD = "mtop.alisports.play.dog.common.listplayingrecord";
    public static final String API_CHANGE_RECORD = "mtop.alisports.play.dog.common.changeplayingrecord";
    public static final String API_QUERY_AWARD = "mtop.alisports.play.dog.common.queryAward";
    public static final String API_CHOOSE_AWARD = "mtop.alisports.play.dog.common.chooseAwardPlay";
    public static final String API_TASK_MODULE = "mtop.alisports.messi.taskcenter.getTaskModuleInfo";

    /**
     * 任务奖励(体力)领取接口。
     *
     * <p>⚠️ 抓包日志里**没有**出现过领取动作: 真机是在活动页/任务中心(java 侧不可见的 H5 页面)
     * 点的「领取」。这里的接口名是同类 alisports 任务中心的命名推断, 未经抓包确认,
     * 因此默认关闭(见任务开关「自动领取任务体力」)。若真机日志显示该调用失败,
     * 把活动页点「领取」那一刻的抓包补进来即可替换此常量。
     */
    public static final String API_CLAIM_TASK = "mtop.alisports.messi.taskcenter.receiveTaskAward";

    /** 需要 token 的调用。 */
    public interface TokenCall {
        MtopSender.Result run(String token);
    }

    // ═══════════════════════════ 链路接口 ═══════════════════════════

    /** 进入活动页(判定 routeRedirectType, 兼作活动可用性探测)。 */
    public static MtopSender.Result pageroute() {
        try {
            JSONObject extra = new JSONObject();
            extra.put("chn", CHN);
            extra.put("version", VERSION);
            extra.put("entranceFlag", true);
            JSONObject data = new JSONObject();
            data.put("extraData", extra.toString());
            // 抓包里 pageroute 也带 alty_ext/alty_sso_token; 拿不到 token 也不阻断(该接口本身不校验)
            String token = SesameDogAuth.peekToken();
            return MtopSender.send(API_PAGEROUTE, VERSION, data.toString(), false,
                    StringUtil.isEmpty(token) ? null : authHeaders(token));
        } catch (Throwable t) {
            Log.err(TAG, "pageroute err:", t);
            return null;
        }
    }

    /**
     * 活动状态: 返回 PLAYING 才能前进(体育游乐记特有前置)。
     * 抓包: data 直接是字符串 {@code "PLAYING"}。
     */
    public static MtopSender.Result playState() {
        try {
            JSONObject data = new JSONObject();
            String token = SesameDogAuth.peekToken();
            return MtopSender.send(API_PLAY_STATE, VERSION, data.toString(), false,
                    StringUtil.isEmpty(token) ? null : authHeaders(token));
        } catch (Throwable t) {
            Log.err(TAG, "playState err:", t);
            return null;
        }
    }

    /** 活动首页: 当前城市/记录/节点状态, 体力余额(points)与单次消耗(singleConsumePoints)。 */
    public static MtopSender.Result index() {
        return withToken(new TokenCall() {
            @Override
            public MtopSender.Result run(String token) {
                try {
                    JSONObject extend = new JSONObject();
                    extend.put("exposureNum", 0);
                    extend.put("chn", CHN);
                    extend.put("dogVersion", "3.0");
                    JSONObject data = new JSONObject();
                    data.put("extendData", extend.toString());
                    return MtopSender.send(API_INDEX, VERSION, data.toString(), false, authHeaders(token));
                } catch (Throwable t) {
                    Log.err(TAG, "index err:", t);
                    return null;
                }
            }
        });
    }

    /**
     * 刷新/结算体力(抓包实测的「领体力」入口: 活动页点体力条会打这个接口)。
     * needWua=true; 返回 {@code points}(结算后余额) 与 {@code singleConsumePoints}。
     */
    public static MtopSender.Result refreshPoints() {
        return withToken(new TokenCall() {
            @Override
            public MtopSender.Result run(String token) {
                try {
                    return MtopSender.send(API_REFRESH_POINTS, VERSION, "{}", true, authHeaders(token));
                } catch (Throwable t) {
                    Log.err(TAG, "refreshPoints err:", t);
                    return null;
                }
            }
        });
    }

    /** 前进一次(消耗 singleConsumePoints 体力, 涨站点进度)。 */
    public static MtopSender.Result forward(final String playingKey) {
        return withToken(new TokenCall() {
            @Override
            public MtopSender.Result run(String token) {
                try {
                    JSONObject extend = new JSONObject();
                    extend.put("chn", CHN);
                    extend.put("dogVersion", "3.1");
                    extend.put("exposureNum", JSONObject.NULL);
                    JSONObject data = new JSONObject();
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

    /** 我参与过的城市游玩记录(含 progress / usedPoints / siteConfig)。 */
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

    /** 切换当前游玩记录(城市), 参数名是 playingRecordId(抓包为数值型)。 */
    public static MtopSender.Result changeRecord(final String playingRecordId) {
        return withToken(new TokenCall() {
            @Override
            public MtopSender.Result run(String token) {
                try {
                    JSONObject data = new JSONObject();
                    data.put("playingRecordId", recordIdValue(playingRecordId));
                    return MtopSender.send(API_CHANGE_RECORD, VERSION, data.toString(), false, authHeaders(token));
                } catch (Throwable t) {
                    Log.err(TAG, "changeRecord err:", t);
                    return null;
                }
            }
        });
    }

    /** 可领奖城市列表(切换城市前的选奖页)。 */
    public static MtopSender.Result queryAward() {
        return withToken(new TokenCall() {
            @Override
            public MtopSender.Result run(String token) {
                try {
                    JSONObject extend = new JSONObject();
                    extend.put("channelSource", "multiPlaying");
                    JSONObject data = new JSONObject();
                    data.put("extendData", extend.toString());
                    data.put("pageSize", 20);
                    return MtopSender.send(API_QUERY_AWARD, VERSION, data.toString(), false, authHeaders(token));
                } catch (Throwable t) {
                    Log.err(TAG, "queryAward err:", t);
                    return null;
                }
            }
        });
    }

    /** 选定某城市的奖励(切城市流程里的最后一步)。 */
    public static MtopSender.Result chooseAwardPlay(final String awardKey) {
        return withToken(new TokenCall() {
            @Override
            public MtopSender.Result run(String token) {
                try {
                    JSONObject extend = new JSONObject();
                    extend.put("chn", CHN);
                    JSONObject data = new JSONObject();
                    data.put("awardKey", awardKey == null ? "" : awardKey);
                    data.put("extendData", extend.toString());
                    return MtopSender.send(API_CHOOSE_AWARD, VERSION, data.toString(), false, authHeaders(token));
                } catch (Throwable t) {
                    Log.err(TAG, "chooseAwardPlay err:", t);
                    return null;
                }
            }
        });
    }

    /** 活动任务中心: 任务清单与完成/领取状态(奖励体力 DOG_POINT)。 */
    public static MtopSender.Result taskModuleInfo() {
        return taskModuleInfo(TASK_ACTIVITY_ID);
    }

    public static MtopSender.Result taskModuleInfo(final String activityId) {
        return withToken(new TokenCall() {
            @Override
            public MtopSender.Result run(String token) {
                try {
                    JSONObject data = new JSONObject();
                    data.put("taskActivityId", activityId == null ? "" : activityId);
                    return MtopSender.send(API_TASK_MODULE, VERSION, data.toString(), false, authHeaders(token));
                } catch (Throwable t) {
                    Log.err(TAG, "taskModuleInfo err:", t);
                    return null;
                }
            }
        });
    }

    /** 领取已完成任务的体力奖励(接口名未确认, 见 {@link #API_CLAIM_TASK} 说明)。 */
    public static MtopSender.Result claimTaskReward(final String taskId, final String pitId) {
        return withToken(new TokenCall() {
            @Override
            public MtopSender.Result run(String token) {
                try {
                    JSONObject data = new JSONObject();
                    data.put("taskActivityId", TASK_ACTIVITY_ID);
                    data.put("taskId", taskId == null ? "" : taskId);
                    data.put("pitId", pitId == null ? "" : pitId);
                    return MtopSender.send(API_CLAIM_TASK, VERSION, data.toString(), false, authHeaders(token));
                } catch (Throwable t) {
                    Log.err(TAG, "claimTaskReward err:", t);
                    return null;
                }
            }
        });
    }

    // ═══════════════════════════ 任务中心解析 ═══════════════════════════

    /** 任务中心任务: 奖励体力值从 subAddInfo("1000体力") 里取。 */
    public static final class TaskItem {

        public final String taskId;
        public final String pitId;
        public final String type;
        public final String title;
        public final String status;
        public final int reward;
        public final int finishCount;
        public final int goalFinishCount;
        public final boolean online;

        TaskItem(String taskId, String pitId, String type, String title, String status, int reward,
                 int finishCount, int goalFinishCount, boolean online) {
            this.taskId = taskId;
            this.pitId = pitId;
            this.type = type;
            this.title = title;
            this.status = status;
            this.reward = reward;
            this.finishCount = finishCount;
            this.goalFinishCount = goalFinishCount;
            this.online = online;
        }

        /** 任务目标是否已达(regardless of 领取)。 */
        public boolean done() {
            return goalFinishCount > 0 && finishCount >= goalFinishCount;
        }

        /** 抓包实测: status=2 表示已完成并已领取。 */
        public boolean claimed() {
            return "2".equals(status);
        }

        /** 已完成但还没领: 需要去活动页点「领取」的那类。 */
        public boolean claimable() {
            return done() && !claimed() && online;
        }

        @Override
        public String toString() {
            return title + "[" + type + ", " + reward + "体力, 完成 " + finishCount + "/" + goalFinishCount
                    + ", status=" + status + "]";
        }
    }

    /** 解析任务中心返回的任务列表(失败返回空表)。 */
    public static List<TaskItem> parseTasks(MtopSender.Result result) {
        List<TaskItem> list = new ArrayList<>();
        if (result == null || !result.ok) {
            return list;
        }
        JSONObject body = result.body();
        JSONArray groups = body == null ? null : body.optJSONArray("groups");
        if (groups == null) {
            return list;
        }
        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            JSONArray tasks = group == null ? null : group.optJSONArray("tasks");
            if (tasks == null) {
                continue;
            }
            for (int j = 0; j < tasks.length(); j++) {
                JSONObject task = tasks.optJSONObject(j);
                if (task == null) {
                    continue;
                }
                String id = task.optString("taskId");
                if (StringUtil.isEmpty(id)) {
                    continue;
                }
                String sub = task.optString("subAddInfo");
                list.add(new TaskItem(id, task.optString("pitId"), task.optString("taskType"),
                        task.optString("taskMainHead"), task.optString("status"), rewardOf(sub),
                        toInt(task.optString("finishCount"), 0), toInt(task.optString("goalFinishCount"), 0),
                        "1".equals(task.optString("taskOnlineStatus"))));
            }
        }
        return list;
    }

    /** 今日已完成的任务数(任务中心字段 taskFinishedCountToday)。 */
    public static int finishedToday(MtopSender.Result result) {
        if (result == null || !result.ok) {
            return -1;
        }
        JSONObject body = result.body();
        return body == null ? -1 : toInt(body.optString("taskFinishedCountToday"), -1);
    }

    /** 从 "3000体力" 这类文案里抠出体力数。 */
    private static int rewardOf(String subAddInfo) {
        if (StringUtil.isEmpty(subAddInfo)) {
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < subAddInfo.length(); i++) {
            char c = subAddInfo.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            } else if (sb.length() > 0) {
                break;
            }
        }
        return toInt(sb.toString(), 0);
    }

    private static int toInt(String text, int def) {
        if (StringUtil.isEmpty(text)) {
            return def;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (Throwable ignore) {
            return def;
        }
    }

    // ═══════════════════════════ 公共 ═══════════════════════════

    /** 体育游乐记接口统一请求头: alty_ext + alty_sso_token。 */
    public static java.util.Map<String, String> authHeaders(String token) {
        return SesameDogAuth.ssoHeaders(token);
    }

    /** playingRecordId 抓包是数值, 能转数值就按数值发, 避免服务端类型校验失败。 */
    private static Object recordIdValue(String id) {
        if (StringUtil.isEmpty(id)) {
            return "";
        }
        try {
            return Long.parseLong(id.trim());
        } catch (Throwable ignore) {
            return id;
        }
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
            Log.record("体育游乐记: 接口返回 " + last.brief() + ", 作废 token 后重试一次");
            SesameDogAuth.invalidate();
        }
        return last;
    }
}
