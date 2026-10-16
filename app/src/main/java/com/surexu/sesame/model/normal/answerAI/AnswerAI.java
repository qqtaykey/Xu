package com.surexu.sesame.model.normal.answerAI;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.surexu.sesame.data.ConfigV2;
import com.surexu.sesame.data.Model;
import com.surexu.sesame.data.ModelField;
import com.surexu.sesame.data.ModelFields;
import com.surexu.sesame.data.ModelGroup;
import com.surexu.sesame.data.TokenConfig;
import com.surexu.sesame.data.ViewAppInfo;
import com.surexu.sesame.data.modelFieldExt.BooleanModelField;
import com.surexu.sesame.data.modelFieldExt.EmptyModelField;
import com.surexu.sesame.data.modelFieldExt.IntegerModelField;
import com.surexu.sesame.data.modelFieldExt.StringModelField;
import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.StringUtil;
import com.surexu.sesame.util.ToastUtil;
import com.surexu.sesame.util.idMap.UserIdMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.json.JSONArray;
import org.json.JSONObject;

public class AnswerAI extends Model {

    private static final String TAG = AnswerAI.class.getSimpleName();

    /** 任务线程读取、boot 线程写入，用 volatile 保证可见性 */
    private static volatile Boolean enable = false;

    /** 连通性自检提示词 */
    private static final String AI_TEST_PROMPT = "这是一次接口连通性测试。请只回复 OK。";
    /** 自检结果回显到 Toast 的最大长度 */
    private static final int AI_TEST_RESULT_MAX_LENGTH = 120;

    /** AI 指令解析提示词前缀：把功能清单连同当前开关状态一起交给模型，模型既能下指令、也能回答"XX开了吗" */
    private static final String AI_COMMAND_PROMPT_PREFIX =
            "你是支付宝芝麻粒自动化模块的语音控制助手。请判断用户这句话是在查询或修改功能开关，"
                    + "只输出一个 JSON 对象，不要输出解释、不要用 markdown 代码块。\n"
                    + "可用功能清单（模型名[总开关:开/关]，其后为子功能名:开/关）：\n";
    /** AI 指令解析提示词后缀：约束输出格式与边界，防止模型编造功能名或擅自批量操作 */
    private static final String AI_COMMAND_PROMPT_SUFFIX =
            "\n输出格式：{\"handled\":true,\"reply\":\"给用户看的简短中文回复\","
                    + "\"actions\":[{\"model\":\"模型名\",\"field\":\"子功能名，空字符串表示模型总开关\",\"enable\":true}]}\n"
                    + "规则：\n"
                    + "1. 模型名、子功能名只能取清单里出现过的，不得编造；\n"
                    + "2. 只是查询状态、没有开关动作时 handled=true 且 actions 为空数组，reply 按清单里的状态直接回答；\n"
                    + "3. 与功能开关完全无关的问题 handled=false，reply 留空；\n"
                    + "4. 用户只说\"全部\"\"所有功能\"但没指明是哪个模块时不要擅自操作，handled=true、actions 为空，reply 请用户补充范围；\n"
                    + "5. reply 要简短，说清改了哪些开关、或当前是什么状态。\n"
                    + "用户的话：";

    /** 日志里题目与选项列表的最大字数：整题与全部选项都写进日志会显著撑大日志 */
    private static final int LOG_TITLE_MAX_LENGTH = 60;
    private static final int LOG_OPTIONS_MAX_LENGTH = 80;

    @Override
    public String getName() {
        return "AI答";
    }

    @Override
    public ModelGroup getGroup() {
        return ModelGroup.OTHER;
    }

    /** 当前生效的自定义AI实现；未配置时为 null，表示不调用AI */
    private static volatile CustomAI customAI;

    private final StringModelField customAIUrl = new StringModelField("customAIUrl", "自定义AI | 接口地址(根地址,如/v1)", "");
    private final StringModelField customAIModel = new StringModelField("customAIModel", "自定义AI | 模型名", "");
    private final StringModelField customAIKey = new StringModelField("customAIKey", "自定义AI | 令牌", "");
    private final IntegerModelField customAIMaxTokens = new IntegerModelField("customAIMaxTokens", "自定义AI | 输出Token上限(0=不发)", 1024, 0, 8192);
    private final EmptyModelField customAITest = new EmptyModelField("customAITest", "自定义AI | 测试响应", this::testConnection);

    @Override
    public ModelFields getFields() {
        ModelFields modelFields = new ModelFields();
        modelFields.addField(customAIUrl);
        modelFields.addField(customAIModel);
        modelFields.addField(customAIKey);
        modelFields.addField(customAIMaxTokens);
        modelFields.addField(customAITest);
        return modelFields;
    }

    @Override
    public void boot(ClassLoader classLoader) {
        enable = getEnableField().getValue();
        customAI = new CustomAI(customAIUrl.getValue(), customAIModel.getValue(), customAIKey.getValue(), customAIMaxTokens.getValue());
        if (!customAI.isConfigured()) {
            customAI = null;
            Log.record("AI🧠接口地址/模型名/令牌未填齐，答题不会调用AI，将直接使用题库或首个选项");
        }
    }

    /**
     * 「测试响应」按钮：用当前填写的配置发一次最简单的请求，结果用 Toast 强制回显，
     * 让用户立刻确认地址/模型/令牌是否可用，不必去翻日志。
     * <p>
     * 网络请求放到子线程，避免在主线程阻塞或抛 NetworkOnMainThreadException。
     */
    private void testConnection() {
        CustomAI tempAI = new CustomAI(customAIUrl.getValue(), customAIModel.getValue(), customAIKey.getValue(), customAIMaxTokens.getValue());
        if (!tempAI.isConfigured()) {
            showToast("请先填写接口地址、模型名与令牌");
            return;
        }
        showToast("正在测试AI接口...");
        new Thread(() -> {
            String result = tempAI.getAnswerStr(AI_TEST_PROMPT);
            if (result == null || result.trim().isEmpty()) {
                showToast("AI接口测试失败：地址/模型/令牌有误或请求超时（详见日志）");
                return;
            }
            showToast("AI接口测试成功：" + trimForLog(result, AI_TEST_RESULT_MAX_LENGTH));
        }).start();
    }

    /**
     * 配置页 Toast：用 android.widget.Toast + UI 进程自己的 applicationContext 实现。
     * <p>
     * 不能用模块侧的 {@code hook.Toast}：它内部要走 {@code ApplicationHook}（继承 libxposed 的
     * XposedModule），而模块 App 自己的进程里没有 libxposed API，一调用就 NoClassDefFoundError 闪退。
     * <p>
     * 结果可能来自网络子线程，所以统一 post 回主线程再弹。
     */
    private static void showToast(String text) {
        Context context = ViewAppInfo.getContext();
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> ToastUtil.show(appContext, text));
    }

    /** 日志/Toast 用：压缩空白并超长截断，避免整题与全部选项把日志撑大 */
    private static String trimForLog(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return StringUtil.truncate(text.replaceAll("\\s+", " ").trim(), maxLength);
    }

    /** 按当前配置构造 AI 客户端；未填齐配置时返回 null */
    public static CustomAI buildCustomAI() {
        AnswerAI model = Model.getModel(AnswerAI.class);
        if (model == null) {
            Model.initAllModel();
            model = Model.getModel(AnswerAI.class);
        }
        if (model == null) {
            return null;
        }
        CustomAI ai = new CustomAI(model.customAIUrl.getValue(), model.customAIModel.getValue(), model.customAIKey.getValue(), model.customAIMaxTokens.getValue());
        return ai.isConfigured() ? ai : null;
    }

    /**
     * 聊天页等 UI 复用入口：用当前配置发一次对话请求。
     *
     * @return 模型回答文本；未配置或请求失败返回空串
     */
    public static String ask(String question) {
        try {
            CustomAI ai = buildCustomAI();
            if (ai == null) {
                Log.record("AI🧠聊天未调用：接口地址/模型名/令牌未填齐");
                return "";
            }
            return ai.getAnswerStr(question);
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
            return "";
        }
    }

    /**
     * 聊天页控制类指令入口：把自然语言（如"开启森林收能量"）映射为功能开关并落盘生效。
     * 两级通道：先走规则快通道（零延迟、不耗 token），规则认不出来再交给大模型兜底解析。
     * 注意：AI 兜底会发网络请求（最长约 30 秒），调用方必须在子线程调用本方法。
     *
     * @return 已执行的操作描述或 AI 的回复；未识别为控制指令返回 null（调用方转走 AI 对话）
     */
    public static String executeCommand(String question) {
        String byRule = executeCommandByRule(question);
        if (byRule != null) {
            return byRule;
        }
        // 规则快通道没认出来时交给大模型：口语化说法、一句话多个动作、状态查询都能处理
        return executeCommandByAI(question);
    }

    /**
     * 规则快通道：把标准句式（如"开启森林收能量""把健康岛全部关闭"）映射为功能开关并落盘。命中返回操作描述，未识别返回 null。
     */
    private static String executeCommandByRule(String question) {
        try {
            if (question == null) {
                return null;
            }
            question = question.trim();
            if (question.isEmpty()) {
                return null;
            }
            String q = question.replaceAll("[\\s，。,.！!？?、；;：:\"'']", "").toLowerCase(Locale.ROOT);
            boolean enable;
            // 否定句式（"别关收能量""不要打开森林"）语义与动作词相反，必须先判，否则会被"关闭/打开"抢先匹配成反方向
            if (containsAny(q, "不要关", "别关", "不用关", "不要关闭", "别关闭")) {
                enable = true;
            } else if (containsAny(q, "不要开", "别开", "不用开", "不要打开", "别打开")) {
                enable = false;
            } else if (containsAny(q, "开启", "打开", "启用", "开一下", "帮我开")) {
                enable = true;
            } else if (containsAny(q, "关闭", "停用", "禁用", "关一下", "帮我关")) {
                enable = false;
            } else {
                return null;
            }
            List<String> changedItems = new ArrayList<>();
            boolean changed = false;
            // 高频子功能同义词：统一替换成字段标准名，口语说法也能命中
            q = q.replace("收取能量", "收集能量").replace("偷能量", "收集能量").replace("收能量", "收集能量")
                    .replace("收金球", "收取金球").replace("收球", "收取金球");
            for (Model model : Model.getModelList()) {
                if (model == null || model instanceof AnswerAI || StringUtil.isEmpty(model.getName())) {
                    continue;
                }
                String modelName = model.getName();
                // 模型匹配：全名连续出现优先，否则用最长连续前缀兜底（"开启健康岛"命中"健康岛红包碎片兑换"）
                boolean modelHit = q.contains(modelName);
                if (!modelHit) {
                    for (int len = Math.min(3, modelName.length()); len >= 2; len--) {
                        if (q.contains(modelName.substring(0, len))) {
                            modelHit = true;
                            break;
                        }
                    }
                }
                if (!modelHit) {
                    continue;
                }
                // 子功能匹配：先移除模型名并剔除动作词，剩余文本描述具体功能
                String rest = q.contains(modelName) ? q.replace(modelName, "") : q;
                rest = rest.replace("开启", "").replace("打开", "").replace("启用", "")
                        .replace("开一下", "").replace("帮我开", "")
                        .replace("关闭", "").replace("停用", "").replace("禁用", "")
                        .replace("关一下", "").replace("帮我关", "");
                // 字段引用必须取 ConfigV2 缓存的权威对象：load() 时已把用户配置写入这批对象，
                // save() 的 collectChangedFields() 也按这批对象比对。不能走 model.getFields()——
                // AntFarm/AntForestV2 等实现每次调用都 new 字段对象并覆盖实例字段，既让任务运行期
                // 读到默认值，又使这里的 setObjectValue 落在孤儿对象上、保存时收集不到改动。
                ModelFields fields = ConfigV2.INSTANCE.getModelFieldsMap().get(model.getClass().getSimpleName());
                if (fields == null) {
                    continue;
                }
                List<String> fieldHits = new ArrayList<>();
                List<String> fieldHitNames = new ArrayList<>();
                // "把森林全部关闭""健康岛所有功能打开"：指定了模型又说了全部/所有，视为该模型下所有子功能。
                // 只认"全部/所有/全都"这类明确词，不用"都"这种口语泛词，避免"把庄园和森林都关了"这种整片误伤。
                boolean allFields = containsAny(q, "全部", "所有", "全都");
                if (rest.length() >= 2 || allFields) {
                    for (Map.Entry<String, ModelField<?>> entry : fields.entrySet()) {
                        ModelField<?> field = entry.getValue();
                        // ModelFields 以字段 code 为键；enable 为模型总开关，单独处理，避免被当子功能重复命中
                        if (!(field instanceof BooleanModelField) || StringUtil.isEmpty(field.getCode())
                                || "enable".equals(field.getCode()) || StringUtil.isEmpty(field.getName())) {
                            continue;
                        }
                        // 字段名可能含" | "分隔（分组 | 功能），任一部分与剩余文本互为连续子串即命中
                        boolean fieldHit = allFields;
                        if (!fieldHit) {
                            for (String part : field.getName().split("\\|")) {
                                String p = part.replaceAll("[\\s（）()]", "");
                                if (p.length() >= 2 && (p.contains(rest) || rest.contains(p))) {
                                    fieldHit = true;
                                    break;
                                }
                            }
                        }
                        if (fieldHit) {
                            fieldHits.add(field.getCode());
                            fieldHitNames.add(field.getName());
                        }
                    }
                }
                // 顺带命中模型（rest 非空且无任何子功能命中）视为误匹配，跳过不开
                if (rest.isEmpty() || !fieldHits.isEmpty()) {
                    BooleanModelField enableField = model.getEnableField();
                    if (enableField != null && Boolean.TRUE.equals(enableField.getValue()) != enable) {
                        enableField.setObjectValue(enable);
                        changed = true;
                    }
                    changedItems.add(modelName);
                    for (int i = 0; i < fieldHits.size(); i++) {
                        ModelField<?> hitField = fields.get(fieldHits.get(i));
                        if (hitField instanceof BooleanModelField) {
                            BooleanModelField boolField = (BooleanModelField) hitField;
                            if (Boolean.TRUE.equals(boolField.getValue()) != enable) {
                                boolField.setObjectValue(enable);
                                changed = true;
                            }
                        }
                        changedItems.add(modelName + "·" + fieldHitNames.get(i));
                    }
                }
            }
            if (changedItems.isEmpty()) {
                return null;
            }
            if (changed) {
                // 必须落盘到「当前登录账号」的配置文件：原来传 null 会写默认配置(默认.json)，
                // 当前账号的配置没被更新，重启/切换后语音开关的改动就丢了
                ConfigV2.save(UserIdMap.getCurrentUid(), false);
                restartSesame();
            }
            return (enable ? "已开启：" : "已关闭：") + String.join("、", changedItems);
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
            return null;
        }
    }

    /**
     * 大模型兜底解析：把整句话连同功能清单交给 AI，要求回结构化 JSON，再由本地校验后执行。
     * AI 未配置、请求失败或判定为无关指令时返回 null（调用方转走普通 AI 对话）。
     */
    private static String executeCommandByAI(String question) {
        try {
            if (question == null || question.trim().isEmpty()) {
                return null;
            }
            // AI 兜底同样受"AI答"总开关约束：用户关掉 AI 后就不再走网络解析
            if (!Boolean.TRUE.equals(enable)) {
                return null;
            }
            CustomAI ai = customAI != null ? customAI : buildCustomAI();
            if (ai == null) {
                return null;
            }
            String catalog = buildCommandCatalog();
            if (catalog.isEmpty()) {
                return null;
            }
            String result = ai.getAnswerStr(AI_COMMAND_PROMPT_PREFIX + catalog + AI_COMMAND_PROMPT_SUFFIX + question);
            if (result == null || result.trim().isEmpty()) {
                return null;
            }
            JSONObject jo = parseJsonObject(result);
            if (jo == null) {
                Log.record("AI指令🧠返回内容不是JSON，转普通对话");
                return null;
            }
            if (!jo.optBoolean("handled", false)) {
                return null;
            }
            List<String> changedItems = new ArrayList<>();
            JSONArray actions = jo.optJSONArray("actions");
            for (int i = 0; actions != null && i < actions.length(); i++) {
                JSONObject action = actions.optJSONObject(i);
                if (action != null) {
                    applyAction(action.optString("model", ""), action.optString("field", ""),
                            action.optBoolean("enable", true), changedItems);
                }
            }
            if (!changedItems.isEmpty()) {
                ConfigV2.save(UserIdMap.getCurrentUid(), false);
                restartSesame();
            }
            String reply = jo.optString("reply", "").trim();
            if (!reply.isEmpty()) {
                Log.record("AI指令🧠" + trimForLog(reply, LOG_OPTIONS_MAX_LENGTH));
                return reply;
            }
            return changedItems.isEmpty() ? null : "已设置：" + String.join("、", changedItems);
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
            return null;
        }
    }

    /**
     * 执行 AI 下发的单个动作：模型名/字段名必须能在本地配置里找到，找不到就忽略，防止模型编造导致误改配置。
     * field 为空串表示操作该模型的总开关。
     */
    private static void applyAction(String modelName, String fieldName, boolean enable, List<String> changedItems) {
        if (StringUtil.isEmpty(modelName)) {
            return;
        }
        for (Model model : Model.getModelList()) {
            if (model == null || model instanceof AnswerAI || StringUtil.isEmpty(model.getName())) {
                continue;
            }
            String name = model.getName();
            if (!name.equals(modelName) && !name.contains(modelName) && !modelName.contains(name)) {
                continue;
            }
            if (StringUtil.isEmpty(fieldName)) {
                BooleanModelField enableField = model.getEnableField();
                if (enableField != null && Boolean.TRUE.equals(enableField.getValue()) != enable) {
                    enableField.setObjectValue(enable);
                }
                changedItems.add(name);
                return;
            }
            ModelFields fields = ConfigV2.INSTANCE.getModelFieldsMap().get(model.getClass().getSimpleName());
            if (fields == null) {
                return;
            }
            for (Map.Entry<String, ModelField<?>> entry : fields.entrySet()) {
                ModelField<?> field = entry.getValue();
                // 与规则通道保持一致：只认布尔子功能，enable 是模型总开关另行处理
                if (!(field instanceof BooleanModelField) || StringUtil.isEmpty(field.getCode())
                        || "enable".equals(field.getCode()) || StringUtil.isEmpty(field.getName())) {
                    continue;
                }
                if (!nameMatches(field.getName(), fieldName)) {
                    continue;
                }
                BooleanModelField boolField = (BooleanModelField) field;
                if (Boolean.TRUE.equals(boolField.getValue()) != enable) {
                    boolField.setObjectValue(enable);
                }
                changedItems.add(name + "·" + field.getName());
                return;
            }
            return;
        }
    }

    /** 生成给 AI 的功能清单：模型名[总开关]＋各子功能名与当前状态，只列布尔子功能，尽量紧凑省 token */
    private static String buildCommandCatalog() {
        StringBuilder sb = new StringBuilder();
        for (Model model : Model.getModelList()) {
            if (model == null || model instanceof AnswerAI || StringUtil.isEmpty(model.getName())) {
                continue;
            }
            ModelFields fields = ConfigV2.INSTANCE.getModelFieldsMap().get(model.getClass().getSimpleName());
            if (fields == null) {
                continue;
            }
            StringBuilder modelText = new StringBuilder();
            for (Map.Entry<String, ModelField<?>> entry : fields.entrySet()) {
                ModelField<?> field = entry.getValue();
                if (!(field instanceof BooleanModelField) || StringUtil.isEmpty(field.getCode())
                        || "enable".equals(field.getCode()) || StringUtil.isEmpty(field.getName())) {
                    continue;
                }
                // 字段名可能是"分组 | 功能"，只取最后一段当简称：模型回传的也是这个简称，省 token
                String[] parts = field.getName().split("\\|");
                modelText.append('；').append(parts[parts.length - 1].trim())
                        .append(':').append(stateText((BooleanModelField) field));
            }
            // 即使一个子功能都没有也列出模型自身，保证"打开/关闭XX"这类总开关指令也能被识别
            sb.append(model.getName()).append('[').append(stateText(model.getEnableField())).append(']')
                    .append(modelText).append('\n');
        }
        return sb.toString();
    }

    /** 开关状态文本：字段缺失（null）时按关处理 */
    private static String stateText(BooleanModelField field) {
        return field != null && Boolean.TRUE.equals(field.getValue()) ? "开" : "关";
    }

    /** 字段名（可能是"分组 | 功能"）与 AI 回传的名字：任一非空片段互相包含即视为同一功能 */
    private static boolean nameMatches(String fieldName, String target) {
        for (String part : fieldName.split("\\|")) {
            String p = part.trim();
            if (!p.isEmpty() && (p.equals(target) || p.contains(target) || target.contains(p))) {
                return true;
            }
        }
        return false;
    }

    /** 从 AI 回复里截出 JSON 对象：模型常会带前后说明或 ```json 包裹 */
    private static JSONObject parseJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return new JSONObject(text.substring(start, end + 1));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 广播通知宿主重载配置：开关改动要重启芝麻粒任务才生效 */
    private static void restartSesame() {
        try {
            Context context = ViewAppInfo.getContext();
            if (context != null) {
                context.sendBroadcast(new Intent("com.eg.android.AlipayGphone.sesame.restart"));
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean containsAny(String q, String... keys) {
        for (String key : keys) {
            if (q.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取答案
     *
     * @param text       问题
     * @param answerList 答案集合
     * @return 选中的选项文本；AI 不可用或未返回有效答案时取第一个选项，选项集合为空时返回空串
     */
    public static String getAnswer(String text, List<String> answerList) {
        String answerStr = "";
        try {
            // 题目与选项都截断：整题 + 全部选项全量写入会显著撑大日志
            Log.record("知识问答🧠题目[" + trimForLog(text, LOG_TITLE_MAX_LENGTH)
                    + "]#共" + answerList.size() + "项" + trimForLog(answerList.toString(), LOG_OPTIONS_MAX_LENGTH));
            // enable 是 Boolean，配置缺失时为 null，用 TRUE.equals 避免拆箱 NPE
            if (Boolean.TRUE.equals(enable) && customAI != null) {
                Integer answer = customAI.getAnswer(text, answerList);
                if (answer != null && answer >= 0 && answer < answerList.size()) {
                    answerStr = answerList.get(answer);
                    Log.record("智能回答🧠[" + answerStr + "]");
                } else {
                    Log.record("AI🧠未返回有效答案");
                }
            } else {
                Log.record("AI🧠未启用或未配置，不使用AI作答");
            }
            // AI 不可用、未返回有效答案时统一兜底取第一个选项，并记录原因便于排查
            if (answerStr.isEmpty() && !answerList.isEmpty()) {
                answerStr = answerList.get(0);
                Log.record("兜底回答🤖[" + answerStr + "]");
            }
            // 题库纠错：TokenConfig 里存的是服务端回传过的正确答案（庄园答题结束后会带出次日题目与答案），
            // 命中时以它为准覆盖 AI 的结果；但必须仍在候选选项内，否则题目变了会提交无效答案。
            // 放在 try 内：题库查询异常只该少一次纠错，不能让整条答题失败
            String doubleCheckAnswer = TokenConfig.getAnswer(text);
            if (doubleCheckAnswer != null && !Objects.equals(answerStr, doubleCheckAnswer)) {
                if (answerList.contains(doubleCheckAnswer)) {
                    answerStr = doubleCheckAnswer;
                    Log.record("检测即将提交错误的回答，已自动纠正!新回答:" + answerStr);
                } else {
                    Log.record("题库答案[" + doubleCheckAnswer + "]不在选项内，忽略本次纠错");
                }
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
        return answerStr;
    }

}
