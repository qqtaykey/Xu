package com.surexu.sesame.model.task.antOrchard;

import android.util.Base64;

import org.json.JSONObject;

import java.util.List;

import com.surexu.sesame.hook.ApplicationHook;
import com.surexu.sesame.model.base.TaskAlternative;
import com.surexu.sesame.util.RandomUtil;
import com.surexu.sesame.util.StringUtil;
import com.surexu.sesame.util.idMap.UserIdMap;

public class AntOrchardRpcCall {

    private static final String VERSION = "20250812.01";

    /** 抽抽乐（阿肥寻宝记）外链 chInfo：2026-10-21 双十一专场抓包，游戏类任务上报的 source/CH_INFO */
    private static final String DRAW_CH_INFO = "bbnc_mc_xemkib05";

    /** 抽抽乐游戏上报的场景标识（与农场乐园游戏任务链路一致，服务端按 nongchangleyuan 记账） */
    private static final String DRAW_SCENE_ID = "nongchangleyuan";

    public static String orchardIndex() {
        return ApplicationHook.requestString("com.alipay.antfarm.orchardIndex", "[{\"inHomepage\":\"true\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String mowGrassInfo() {
        return ApplicationHook.requestString("com.alipay.antorchard.mowGrassInfo", "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"showRanking\":true,\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String batchHireAnimalRecommend(String orchardUserId) {
        return ApplicationHook.requestString("com.alipay.antorchard.batchHireAnimalRecommend", "[{\"orchardUserId\":\"" + orchardUserId + "\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"sceneType\":\"weed\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String batchHireAnimal(List<String> recommendGroupList) {
        return ApplicationHook.requestString("com.alipay.antorchard.batchHireAnimal", "[{\"recommendGroupList\":[" + String.join(",", recommendGroupList) + "],\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"sceneType\":\"weed\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String extraInfoGet() {
        return ApplicationHook.requestString("com.alipay.antorchard.extraInfoGet", "[{\"from\":\"entry\",\"requestType\":\"NORMAL\",\"sceneCode\":\"FUGUO\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String extraInfoSet() {
        return ApplicationHook.requestString("com.alipay.antorchard.extraInfoSet", "[{\"bizCode\":\"fertilizerPacket\",\"bizParam\":{\"action\":\"queryCollectFertilizerPacket\"},\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String querySubplotsActivity(String treeLevel) {
        return ApplicationHook.requestString("com.alipay.antorchard.querySubplotsActivity", "[{\"activityType\":[\"WISH\",\"BATTLE\",\"HELP_FARMER\",\"DEFOLIATION\",\"CAMP_TAKEOVER\"],\"inHomepage\":false,\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\"," + "\"source" + "\":\"ch_appcenter__chsub_9patch\",\"treeLevel\":\"" + treeLevel + "\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String triggerSubplotsActivity(String activityId, String activityType, String optionKey) {
        return ApplicationHook.requestString("com.alipay.antorchard.triggerSubplotsActivity", "[{\"activityId\":\"" + activityId + "\",\"activityType\":\"" + activityType + "\",\"optionKey\":\"" + optionKey + "\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\"," + "\"source" + "\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String receiveOrchardRights(String activityId, String activityType) {
        return ApplicationHook.requestString("com.alipay.antorchard.receiveOrchardRights", "[{\"activityId\":\"" + activityId + "\",\"activityType\":\"" + activityType + "\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    /* 七日礼包 */
    public static String drawLottery() {
        return ApplicationHook.requestString("com.alipay.antorchard.drawLottery", "[{\"lotteryScene\":\"receiveLotteryPlus\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String orchardSyncIndex() {
        return ApplicationHook.requestString("com.alipay.antorchard.orchardSyncIndex", "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"syncIndexTypes\":\"QUERY_MAIN_ACCOUNT_INFO\",\"version\":\"" + VERSION + "\"}]");
    }

    /**
     * 施肥。{@code plantScene} 必须是当前已切换到的场景（{@code main}/{@code yeb}）：
     * 报文里声明成别的场景，服务端会回 P03「平行场景信息异常」，余额宝(摇钱树)场景就施肥失败。
     */
    public static String orchardSpreadManure(String plantScene, Boolean useBatchSpread, String wua) {
        // 修复：正确格式化布尔值
        String useBatchSpreadStr = Boolean.TRUE.equals(useBatchSpread) ? "true" : "false";
        String scene = (plantScene == null || plantScene.isEmpty()) ? "main" : plantScene;
        return ApplicationHook.requestString("com.alipay.antfarm.orchardSpreadManure", "[{\"plantScene\":\"" + scene + "\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"useBatchSpread\":" + useBatchSpreadStr + ",\"version\":\"" + VERSION + "\",\"wua\":\"" + (wua != null ? wua : "") + "\"}]");
    }

    public static String receiveTaskAward(String sceneCode, String taskType) {
        return ApplicationHook.requestString("com.alipay.antiep.receiveTaskAward", "[{\"ignoreLimit\":false,\"requestType\":\"NORMAL\",\"sceneCode\":\"" + sceneCode + "\",\"source\":\"ch_appcenter__chsub_9patch\",\"taskType\":\"" + taskType + "\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String orchardListTask() {
        return ApplicationHook.requestString("com.alipay.antfarm.orchardListTask", "[{\"plantHiddenMMC\":\"false\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String orchardSign() {
        return ApplicationHook.requestString("com.alipay.antfarm.orchardSign", "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"signScene\":\"ANTFARM_ORCHARD_SIGN_V2\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String finishTask(String sceneCode, String taskType) {
        String userId = UserIdMap.getCurrentUid();
        String args = "[{\"outBizNo\":\"" + userId + System.currentTimeMillis() + "\",\"requestType\":\"NORMAL\",\"sceneCode\":\"" + sceneCode + "\",\"source\":\"ANTFARM_ORCHARD\",\"taskType\":\"" + taskType + "\",\"userId\":\"" + userId + "\",\"version\":\"" + VERSION + "\"}]";
        return ApplicationHook.requestString("com.alipay.antiep.finishTask", args);
    }

    /**
     * 按 bizKey 完成任务（{@code com.alipay.antfarm.doFarmTask}）。
     * <p>乐园游戏类任务（taskId 形如 {@code ORCHARD_NCLY_*}、groupId {@code ORCHARD_NCLY_GAME_IAA} 等）
     * 服务端拒绝 {@code com.alipay.antiep.finishTask}（400000040 不支持rpc调用），与庄园抽抽乐同型；
     * 庄园的正解就是这条接口（2026-09-22 实测 cclyx / ipccl 前缀的游戏任务走它全部成功）。
     */
    public static String doFarmTask(String bizKey, String taskSceneCode) {
        // 另一种实现方案 payload 只剩一份实现，见 TaskAlternative.request（version 沿用本模块的 VERSION）
        return TaskAlternative.request(bizKey, taskSceneCode, VERSION);
    }

    public static String triggerTbTask(String taskId, String taskPlantType) {
        return ApplicationHook.requestString("com.alipay.antfarm.triggerTbTask", "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"taskId\":\"" + taskId + "\",\"taskPlantType\":\"" + taskPlantType + "\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String orchardSeedList() {
        return ApplicationHook.requestString("com.alipay.antfarm.orchardSeedList", "[{\"from\":\"SEED_LIST\",\"page\":0,\"pvuuid\":\"" + System.currentTimeMillis() + "\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcollect__chsub_my-recentlyUsed\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String orchardSelectSeed(String seedCode) {
        return ApplicationHook.requestString("com.alipay.antfarm.orchardSelectSeed", "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"seedCode\":\"" + seedCode + "\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    /* 砸金蛋 */
    public static String queryGameCenter() {
        return ApplicationHook.requestString("com.alipay.antorchard.queryGameCenter", "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String noticeGame(String appId) {
        return ApplicationHook.requestString("com.alipay.antorchard.noticeGame", "[{\"appId\":\"" + appId + "\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String submitUserAction(String gameId) {
        return ApplicationHook.requestString("com.alipay.gamecenteruprod.biz.rpc.v3.submitUserAction", "[{\"actionCode\":\"enterGame\",\"gameId\":\"" + gameId + "\",\"paladinxVersion\":\"2.0.13\",\"source\":\"gameFramework\"}]");
    }

    public static String submitUserPlayDurationAction(String gameAppId, String source) {
        return ApplicationHook.requestString("com.alipay.gamecenteruprod.biz.rpc.v3.submitUserPlayDurationAction", "[{\"gameAppId\":\"" + gameAppId + "\",\"playTime\":32,\"source\":\"" + source + "\",\"statisticTag\":\"\"}]");
    }

    public static String smashedGoldenEgg() {
        return ApplicationHook.requestString("com.alipay.antorchard.smashedGoldenEgg", "[{\"requestType\":\"NORMAL\",\"seneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    /* 助力好友 */
    public static String achieveBeShareP2P(String friendUserId) {
        String shareId = Base64.encodeToString((friendUserId + "-" + RandomUtil.getRandom(5) + "ANTFARM_ORCHARD_SHARE_P2P").getBytes(), Base64.NO_WRAP);
        String args = "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ANTFARM_ORCHARD_SHARE_P2P\",\"shareId\":\"" + shareId + "\",\"source\":\"share\"}]";
        return ApplicationHook.requestString("com.alipay.antiep.achieveBeShareP2P", args);
    }

    public static String switchPlantScene(String sceneName) {
        return ApplicationHook.requestString("com.alipay.antorchard.switchPlantScene", "[{\"plantScene\":\"" + StringUtil.escapeJson(sceneName) + "\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ANTFARM_ORCHARD\"}]");
    }

    public static String choosePrize(String orderId) {
        return ApplicationHook.requestString("com.alipay.antfarmlite.choosePrize", "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"sendOrderId\":\"" + orderId + "\",\"source\":\"ANTFARM_ORCHARD\"}]");
    }

    public static String yebPlantSceneRevenuePage() {
        // gamecenteruprod 系列接口不吃 sceneCode（那是 antorchard 系列的参数），传 ORCHARD 会被服务端
        // 判为 error 3000「系统出错」；同系列的 queryPointBallList 用极简参数即可
        return ApplicationHook.requestString("com.alipay.gamecenteruprod.biz.rpc.v3.yebPlantSceneRevenuePage", "[{}]");
    }

    public static String triggerYebMoneyTree() {
        return ApplicationHook.requestString("com.alipay.yebbffweb.needle.yebHome.moneyTree.trigger", "[{\"sceneType\":\"default\",\"type\":\"trigger\"}]");
    }

    /**
     * 领取回访奖励
     */
    public static String receiveOrchardVisitAward() {
        // 2026-09-22 抓包（logs/chk_orchard 14:20:10）：官方报文带 diversionSource=DEFAULT；
        // 缺这个字段服务端回 102「参数异常」（模块 14:23 实测命中）
        return ApplicationHook.requestString("com.alipay.antorchard.receiveOrchardVisitAward", "[{\"diversionSource\":\"DEFAULT\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }


    public static String queryOptionalPlay() {
        String args1 = "[{\"bizType\":\"ANTORCHARD\",\"commonDegradeFilterRequest\":{\"appMode\":\"normal\",\"deviceLevel\":\"high\",\"initialized\":true,\"platform\":\"Android\",\"unityDeviceLevel\":\"high\"},\"playTypeList\":[\"TASK_TRIGGER\",\"TOP_UP_COUPON\"],\"recentAppRecordList\":[],\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"H5\",\"version\":\"" + VERSION + "\"}]";
        return ApplicationHook.requestString("com.alipay.charitygamecenter.queryOptionalPlay", args1);
    }

    public static String receiveTaskAwardantorchard(int awardCountForReceive, String sceneCode, String taskType) {
        String args1 = "[{\"awardCountForReceive\":" + awardCountForReceive + ",\"ignoreLimit\":true,\"requestType\":\"RPC\",\"sceneCode\":\"" + sceneCode + "\",\"source\":\"antorchard\",\"taskType\":\"" + taskType + "\"}]";
        return ApplicationHook.requestString("com.alipay.antieptask.receiveTaskAwardantorchard", args1);
    }

    /* ==================== 农场轮盘/抽抽乐：做任务抽奖（阿肥寻宝记，2026-09-30 抓包） ==================== */

    /** 进入轮盘活动页：拿 activityId 与剩余抽奖次数 drawAsset.blance
     *  appMode 用本地抓包值 student；上游 1.4.4 从 INT 改回 normal（其 1.4.3 曾用 INT 导致抽抽乐"从未执行"），
     *  如遇空列表/4xx 可把下面几处 appMode 试改为 normal */
    public static String enterDrawActivityantorchard(String activityId, String sceneCode, String source) {
        return ApplicationHook.requestString("com.alipay.antiepdrawprod.enterDrawActivityantorchard", "[{\"activityId\":\"" + activityId + "\",\"context\":{\"appMode\":\"student\"},\"requestType\":\"RPC\",\"sceneCode\":\"" + sceneCode + "\",\"source\":\"" + source + "\"}]");
    }

    /** 轮盘任务列表：sceneCode=ANTORCHARD_DRAW_TIMES_TASK，响应 taskInfoList（iepTaskTracer 带 taskType/taskStatus） */
    public static String listTaskantorchard(String sceneCode, String source) {
        return ApplicationHook.requestString("com.alipay.antieptask.listTaskantorchard", "[{\"extend\":{\"appMode\":\"student\"},\"requestType\":\"RPC\",\"sceneCode\":\"" + sceneCode + "\",\"source\":\"" + source + "\"}]");
    }

    /** 完成轮盘任务（TODO → 得抽奖次数）。outBizNo 格式与抓包一致：taskType_毫秒时间戳_8位随机串 */
    public static String finishTaskantorchard(String taskType, String sceneCode) {
        String outBizNo = taskType + "_" + System.currentTimeMillis() + "_" + RandomUtil.getRandomString(8);
        return ApplicationHook.requestString("com.alipay.antieptask.finishTaskantorchard", "[{\"outBizNo\":\"" + outBizNo + "\",\"sceneCode\":\"" + sceneCode + "\",\"source\":\"antorchard\",\"taskType\":\"" + taskType + "\"}]");
    }

    /** 领取轮盘任务奖励（FINISHED → +N 次抽奖）。与 2026-09-30 抓包一致：不带 awardCountForReceive */
    public static String receiveTaskAwardantorchard(String sceneCode, String taskType) {
        return ApplicationHook.requestString("com.alipay.antieptask.receiveTaskAwardantorchard", "[{\"ignoreLimit\":true,\"requestType\":\"RPC\",\"sceneCode\":\"" + sceneCode + "\",\"source\":\"antorchard\",\"taskType\":\"" + taskType + "\"}]");
    }

    /** 领取抽抽乐任务奖励（与 receiveTaskAwardantorchard 同 facade，抽抽乐场景别名） */
    public static String receiveDrawTaskAwardantorchard(String sceneCode, String taskType) {
        return receiveTaskAwardantorchard(sceneCode, taskType);
    }

    /** 同步轮盘剩余抽奖次数：响应 drawAsset.blance */
    public static String drawSyncantorchard(String activityId, String source) {
        return ApplicationHook.requestString("com.alipay.antiepdrawprod.drawSyncantorchard", "[{\"activityId\":\"" + activityId + "\",\"context\":{\"appMode\":\"student\"},\"requestType\":\"RPC\",\"sceneCode\":\"ANTORCHARD_DRAW_TIMES\",\"source\":\"" + source + "\"}]");
    }

    /** 单次抽奖（抽抽乐场景，逐次抽取并记录奖品） */
    public static String drawantorchard(String activityId, String sceneCode, String source, String userId) {
        return ApplicationHook.requestString("com.alipay.antiepdrawprod.drawantorchard", "[{\"activityId\":\"" + activityId + "\",\"requestType\":\"RPC\",\"sceneCode\":\"" + sceneCode + "\",\"source\":\"" + source + "\",\"userId\":\"" + userId + "\"}]");
    }

    /** 完成抽抽乐任务（互备腿：基于抓包证据，果园任务完成走 com.alipay.antiep.finishTask，NORMAL+userId+version 格式） */
    public static String finishTaskantorchardV2(String taskType, String sceneCode, String userId) {
        String outBizNo = userId + System.currentTimeMillis();
        return ApplicationHook.requestString("com.alipay.antiep.finishTask", "[{\"outBizNo\":\"" + outBizNo + "\",\"requestType\":\"NORMAL\",\"sceneCode\":\"" + sceneCode + "\",\"source\":\"antorchard\",\"taskType\":\"" + taskType + "\",\"userId\":\"" + userId + "\",\"version\":\"20250812.01\"}]");
    }


    /* ==================== 抽抽乐游戏类任务：行为伪造接口（2026-10-21 双十一专场抓包） ==================== */

    /**
     * 游戏资源预热。真机进抽抽乐活动后会对每个游戏任务 appId 各发一次：
     * {@code sceneId=AntOrchard}、{@code sceneContext.subScene=orchard_lottery}、{@code source=抽抽乐 chInfo}。
     */
    public static String gamepreloadConsult(String appId, String source) {
        return ApplicationHook.requestString("com.alipay.gameflash.biz.rpc.gamepreload.consult", "[{\"appId\":\"" + appId + "\",\"deviceLevel\":\"high\",\"networkType\":\"WWAN\",\"sceneContext\":{\"subScene\":\"orchard_lottery\"},\"sceneId\":\"AntOrchard\",\"source\":\"" + source + "\",\"unityDeviceLevel\":\"high\"}]");
    }

    /**
     * 游戏事件上报（GAME_FIRST_FRAME / loading_completed / game_play）。
     * 属性按抽抽乐场景填（CH_INFO=抽抽乐 chInfo、SCENE_ID=nongchangleyuan、PALADINX_VERSION 与游戏链路一致）。
     * {@code source} 传 null 时按事件类型取与农场乐园游戏链路相同的默认值。
     */
    public static String submitDrawGameEvent(String appId, String eventId, long elapsedTime, String source) {
        try {
            String eventSource = source;
            if (eventSource == null || eventSource.isEmpty()) {
                eventSource = "loading_completed".equals(eventId) ? "my.reportLoadingCompleted" : "my.reportGamePlay";
            }
            JSONObject eventAttrMap = new JSONObject();
            eventAttrMap.put("CH_INFO", DRAW_CH_INFO);
            eventAttrMap.put("CPS_ID", "unknown");
            eventAttrMap.put("GAME_VERSION", "6.5.72");
            eventAttrMap.put("PALADINX_VERSION", "2.1.95");
            eventAttrMap.put("PLAY_SCENE", "NORMAL");
            eventAttrMap.put("SCENE_ID", DRAW_SCENE_ID);
            if ("GAME_FIRST_FRAME".equals(eventId)) {
                eventAttrMap.put("ALIVE_ENTER", "0");
            }
            if (elapsedTime > 0) {
                eventAttrMap.put("GAME_ELAPASED_TIME", elapsedTime);
            }
            JSONObject body = new JSONObject();
            body.put("appId", appId);
            body.put("eventAttrMap", eventAttrMap);
            body.put("eventId", eventId);
            body.put("idempotentNo", "platform_" + System.currentTimeMillis() + "_" + RandomUtil.getRandom(5));
            body.put("source", eventSource);
            return ApplicationHook.requestString("com.alipay.gameevent.biz.rpc.submitEvent", "[" + body + "]");
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 上报游戏时长（抽抽乐游戏类任务伪游玩用）。与农场乐园链路同型：
     * 接口 {@code com.alipay.gamecenteruprod.biz.rpc.v3.submitUserPlayDurationAction}。
     */
    public static String submitDrawGameDuration(String gameAppId, int playTime, String source) {
        return ApplicationHook.requestString("com.alipay.gamecenteruprod.biz.rpc.v3.submitUserPlayDurationAction", "[{\"gameAppId\":\"" + gameAppId + "\",\"playTime\":" + playTime + ",\"source\":\"" + source + "\",\"statisticTag\":\"\"}]");
    }

    /**
     * 带参数的 orchardSyncIndex（适配第二个文件中的调用）
     * 注意：这里参数被忽略，调用无参版本
     */
    public static String orchardSyncIndex(String param) {
        return orchardSyncIndex();
    }

}