package com.surexu.sesame.model.task.antOrchard;

import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.TimeUtil;

import org.json.JSONObject;

/**
 * 阿肥寻宝记（农场抽抽乐，activityId=20261111）任务形态识别与"行为伪造"前置动作。
 *
 * <p>2026-10-21 双十一专场抓包（shared_log_1792513798290，54 次调用）显示，这一期的 6 个任务被拆成了
 * 几种形态，服务端只认"对应的真实行为已发生"，光报完成不认。各形态与处置：
 *
 * <table border="1">
 * <tr><th>任务（sceneCode=ANTORCHARD_DRAW_TIMES_TASK）</th><th>下发形态</th><th>伪上报方式</th></tr>
 * <tr><td>ORCHARD_DRAW_DAILY_SIGN 每日签到</td><td>{@code taskMode=FREE}</td>
 *     <td>无需上报：进活动即由服务端自动置 FINISHED，模块只领奖</td></tr>
 * <tr><td>DRAW_GOLDENBEAN_ZHUHUDONG 双11来淘金币狂享20亿</td><td>{@code NORMAL + CALL_APP_OUT_TASK}</td>
 *     <td>真机是外跳淘金币 H5，模块不外跳，直接报完成；报不动则由统一策略用 doFarmTask 伪申报兜底</td></tr>
 * <tr><td>CHOUCHOULE_GAME_LHS_30s 玩一玩灵画师浏览30s</td>
 *     <td>{@code NORMAL + VISIT_FLOAT_BALL}，{@code prodPlayParam.timeCount=30}</td>
 *     <td>按 timeCount 等够时长再报完成（与森林"新版保护地"同型）</td></tr>
 * <tr><td>2611CCL_GAME_ZH_XDDQ 玩寻道大千砍树20次 / 2611CCL_GAME_ZH_LHS 玩灵画师消耗10包子</td>
 *     <td>{@code NORMAL + OTHER}，{@code prodPlayParam.taskCategorization...game_id} 非空</td>
 *     <td>按游戏中心链路伪造游玩：预热 → 进游戏 → 首帧 → 加载完成 → 模拟时长 → 游玩事件，再报完成</td></tr>
 * <tr><td>CHOUCHOULE_GAME_ZJD5000 攒5000金豆得1次机会</td>
 *     <td>{@code ACC_ANTIEP}，{@code taskRequire=5000}（抓包全程 0/5000）</td>
 *     <td>完成条件是服务端真实计数，伪造推不动：直接交统一策略 doFarmTask 伪申报，且不按"兜底未生效"拉黑</td></tr>
 * </table>
 *
 * <p>本类只负责"把任务要求的真实行为伪造出去"；是否真的完成一律以任务列表为准（由调用点的列表探针复核）。
 * 抓包中不存在任何 finishTask 调用，"伪上报"的请求形状按服务端契约 + 既有同型链路（农场乐园游戏任务、
 * 森林新版保护地）反推，不是抓包直录。
 */
public class AntOrchardDrawTaskForge {

    private static final String TAG = "OrchardChouChouLe";

    /** 抽抽乐外链 chInfo（抓包：任务 targetUrl 的 chInfo，也是 gamepreload 的 source） */
    private static final String CH_INFO = "bbnc_mc_xemkib05";

    /** 浏览计时任务等待上限（秒）：服务端下发异常时长时不把任务线程卡死 */
    private static final long MAX_VISIT_WAIT_SEC = 35L;

    /** 伪游玩上报的时长（秒）：与农场乐园游戏任务链路一致，不小于服务端 30s 门槛 */
    private static final int FAKE_PLAY_SEC = 32;

    private AntOrchardDrawTaskForge() {
    }

    /**
     * 该任务是否"完成条件在服务端真实行为计数上"（如攒5000金豆）。
     * <p>为 true 时统一策略不会因伪申报没推成而按"兜底未生效"拉黑——伪申报本就不是它的完成路径。
     */
    public static boolean isRealAction(JSONObject taskBaseInfo) {
        return isProgressTask(taskBaseInfo);
    }

    /** 任务形态名（日志/说明用） */
    public static String routeOf(JSONObject taskBaseInfo) {
        if (taskBaseInfo == null) {
            return "未知";
        }
        if ("FREE".equals(taskBaseInfo.optString("taskMode"))) {
            return "签到(服务端完成)";
        }
        if ("VISIT_FLOAT_BALL".equals(taskBaseInfo.optString("taskProdPlayType"))) {
            return "浏览计时";
        }
        if (!gameIdOf(taskBaseInfo).isEmpty()) {
            return "伪游玩";
        }
        if ("CALL_APP_OUT_TASK".equals(taskBaseInfo.optString("taskProdPlayType"))) {
            return "外跳类";
        }
        if (isProgressTask(taskBaseInfo)) {
            return "进度计数(仅伪申报)";
        }
        return "通用";
    }

    /**
     * 报完成之前先按任务形态把"真实行为"伪造出去（游戏类伪造游玩、浏览计时类等够时长）。
     * <p>没有可伪造行为的形态（签到 / 外跳 / 进度计数）直接返回，交报完成与统一策略处理。
     *
     * @return true 表示本次已伪造出对应行为
     */
    public static boolean forgeBehavior(JSONObject taskBaseInfo, String taskName) {
        if (taskBaseInfo == null) {
            return false;
        }
        String route = routeOf(taskBaseInfo);
        if ("伪游玩".equals(route)) {
            return forgeGamePlay(gameIdOf(taskBaseInfo), taskName);
        }
        if ("浏览计时".equals(route)) {
            return forgeVisitFloatBall(taskBaseInfo, taskName);
        }
        return false;
    }

    /** 游戏 appId：{@code prodPlayParam.taskCategorization.categorizationParamModel.game_id} */
    static String gameIdOf(JSONObject taskBaseInfo) {
        if (taskBaseInfo == null) {
            return "";
        }
        try {
            JSONObject playParam = new JSONObject(taskBaseInfo.optString("prodPlayParam", "{}"));
            JSONObject categorization = playParam.optJSONObject("taskCategorization");
            JSONObject paramModel = categorization == null
                    ? null : categorization.optJSONObject("categorizationParamModel");
            String gameId = paramModel == null ? "" : paramModel.optString("game_id", "");
            return gameId == null ? "" : gameId.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 完成条件是服务端真实计数（攒金豆类进度任务） */
    private static boolean isProgressTask(JSONObject taskBaseInfo) {
        return taskBaseInfo != null
                && ("ACC_ANTIEP".equals(taskBaseInfo.optString("taskMode"))
                || taskBaseInfo.optInt("taskRequire", 0) > 0);
    }

    /** 游戏类：预热 → 进游戏 → 首帧 → 加载完成 → 模拟游玩时长 → 游玩事件 */
    private static boolean forgeGamePlay(String gameId, String taskName) {
        if (gameId.isEmpty()) {
            return false;
        }
        try {
            AntOrchardRpcCall.gamepreloadConsult(gameId, CH_INFO);
            AntOrchardRpcCall.submitUserAction(gameId);
            TimeUtil.sleep(500);
            AntOrchardRpcCall.submitDrawGameEvent(gameId, "GAME_FIRST_FRAME", 0L, null);
            TimeUtil.sleep(500);
            AntOrchardRpcCall.submitDrawGameEvent(gameId, "loading_completed", 0L, null);
            int playSeconds = 3 + (int) (Math.random() * 2);
            TimeUtil.sleep(playSeconds * 1000L);
            AntOrchardRpcCall.submitDrawGameDuration(gameId, FAKE_PLAY_SEC, CH_INFO);
            AntOrchardRpcCall.submitDrawGameEvent(gameId, "game_play", playSeconds * 1000L, null);
            Log.farm("农场抽抽乐🎮[" + taskName + "]已伪造游玩 " + playSeconds + "s(game_id=" + gameId + ")");
            return true;
        } catch (Throwable t) {
            Log.err(TAG, "forgeGamePlay err:", t);
            return false;
        }
    }

    /** 浏览计时类：按服务端下发的 timeCount 等够时长 */
    private static boolean forgeVisitFloatBall(JSONObject taskBaseInfo, String taskName) {
        long seconds = 0L;
        try {
            JSONObject playParam = new JSONObject(taskBaseInfo.optString("prodPlayParam", "{}"));
            seconds = playParam.optLong("timeCount", 0L);
        } catch (Throwable t) {
            Log.err(TAG, "浏览计时时长解析失败:", t);
        }
        if (seconds <= 0 || seconds > MAX_VISIT_WAIT_SEC) {
            Log.farm("农场抽抽乐⏱️[" + taskName + "]下发时长异常(" + seconds + "s)，跳过等待");
            return false;
        }
        Log.farm("农场抽抽乐⏱️[" + taskName + "]按浏览时长等待 " + seconds + "s 后报完成");
        TimeUtil.sleep(seconds * 1000L);
        return true;
    }
}
