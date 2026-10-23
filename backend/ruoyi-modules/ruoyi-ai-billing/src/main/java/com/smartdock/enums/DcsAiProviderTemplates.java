package com.smartdock.enums;

import com.smartdock.domain.vo.DcsAiProviderTemplateVo;

import java.util.List;

/**
 * Q5-A（2026-10-23 拍板）：20 家厂商模板库。
 *
 * <p>模板只预填「地址 + 格式 + 模型建议」，不写钥匙；provider 仍落现有枚举值，
 * 同一厂商的国内/国际/网关变体用不同 templateId 区分。默认全部走 OpenAI 兼容端点
 * （对话主链可用），需要 Anthropic/Responses 的用户可在表单里改格式做连通测试。</p>
 *
 * @author smartdock
 */
public final class DcsAiProviderTemplates {

    private DcsAiProviderTemplates() {
    }

    private static final String CHAT = DcsAiApiFormat.CHAT_COMPLETIONS.name();

    public static List<DcsAiProviderTemplateVo> list() {
        return List.of(
            t("zhipu-cn", "智谱 BigModel（国内）", "ZHIPU", "https://open.bigmodel.cn/api/paas/v4",
                "glm-4.5-flash", "https://bigmodel.cn/usercenter/proj-mgmt/apikeys",
                List.of("glm-4.5-flash", "glm-4.5", "glm-4-flash"), null),
            t("zhipu-intl", "Z.ai（国际站）", "ZHIPU", "https://api.z.ai/api/paas/v4",
                "glm-4.5-flash", "https://z.ai/manage-apikey/apikey-list",
                List.of("glm-4.5-flash", "glm-4.5"), "国际站域名"),
            t("kimi-cn", "Kimi（国内）", "KIMI", "https://api.moonshot.cn/v1",
                "kimi-k2-0711-preview", "https://platform.moonshot.cn/console/api-keys",
                List.of("kimi-k2-0711-preview", "kimi-latest", "kimi-k2-turbo-preview"), null),
            t("kimi-intl", "Kimi（国际站）", "KIMI", "https://api.moonshot.ai/v1",
                "kimi-k2-0711-preview", "https://platform.moonshot.ai/console/api-keys",
                List.of("kimi-k2-0711-preview", "kimi-latest"), "国际站域名"),
            t("minimax-cn", "MiniMax（国内）", "MINIMAX", "https://api.minimax.cn/v1",
                "MiniMax-M3.1-Flash", "https://platform.minimaxi.com/console/access?tab=api-keys",
                List.of("MiniMax-M3.1-Flash", "MiniMax-M2.7", "MiniMax-M2.5"), null),
            t("minimax-intl", "MiniMax（国际站）", "MINIMAX", "https://api.minimax.io/v1",
                "MiniMax-M3.1-Flash", "https://platform.minimaxi.com/console/access?tab=api-keys",
                List.of("MiniMax-M3.1-Flash"), "国际站域名"),
            t("deepseek", "DeepSeek", "DEEPSEEK", "https://api.deepseek.com/v1",
                "deepseek-chat", "https://platform.deepseek.com/api_keys",
                List.of("deepseek-chat", "deepseek-reasoner"), null),
            t("qwen-cn", "阿里云百炼（国内）", "QWEN", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen-plus", "https://bailian.console.aliyun.com/",
                List.of("qwen-plus", "qwen-max", "qwen-turbo", "qwen3-max"), null),
            t("qwen-intl", "阿里云百炼（国际站）", "QWEN", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
                "qwen-plus", "https://modelstudio.console.aliyun.com/",
                List.of("qwen-plus", "qwen-max", "qwen-turbo"), "国际站域名"),
            t("openai", "OpenAI", "OPENAI", "https://api.openai.com/v1",
                "gpt-4.1-mini", "https://platform.openai.com/api-keys",
                List.of("gpt-4.1-mini", "gpt-4.1", "gpt-4o-mini"), null),
            t("openrouter", "OpenRouter", "OPENROUTER", "https://openrouter.ai/api/v1",
                "deepseek/deepseek-flash-latest", "https://openrouter.ai/keys",
                List.of("deepseek/deepseek-flash-latest", "anthropic/claude-sonnet-4", "openai/gpt-4.1-mini"),
                "聚合网关，免费模型多"),
            t("opencode-go", "OpenCode Go", "OPENCODE", "https://opencode.ai/zen/go/v1",
                "longcat-2.5-preview-free", "https://opencode.ai/auth",
                List.of("longcat-2.5-preview-free", "glm-5.3-flash", "kimi-k3"), "订阅网关"),
            t("opencode-zen", "OpenCode Zen", "OPENCODE", "https://opencode.ai/zen/v1",
                "kimi-k3", "https://opencode.ai/auth",
                List.of("kimi-k3", "minimax-m3", "deepseek-v4-pro"), "订阅网关"),
            t("siliconflow-cn", "硅基流动（国内）", "SILICONFLOW", "https://api.siliconflow.cn/v1",
                "Qwen/Qwen2.5-7B-Instruct", "https://cloud.siliconflow.cn/account/ak",
                List.of("Qwen/Qwen2.5-7B-Instruct", "deepseek-ai/DeepSeek-V3"), null),
            t("siliconflow-intl", "硅基流动（国际站）", "SILICONFLOW", "https://api.siliconflow.com/v1",
                "Qwen/Qwen2.5-7B-Instruct", "https://cloud.siliconflow.com/account/ak",
                List.of("Qwen/Qwen2.5-7B-Instruct"), "国际站域名"),
            t("stepfun", "阶跃星辰 StepFun", "STEPFUN", "https://api.stepfun.com/v1",
                "step-5-preview", "https://platform.stepfun.com/interface-key",
                List.of("step-5-preview", "step-2-16k"), null),
            t("baidu-qianfan", "百度千帆", "BAIDU_QIANFAN", "https://qianfan.baidubce.com/v2",
                "ernie-4.0-8k-latest", "https://console.bce.baidu.com/iam/#/iam/apikey/list",
                List.of("ernie-4.0-8k-latest", "ernie-4.0-turbo-8k"), null),
            t("tencent-hunyuan", "腾讯混元", "TENCENT_HUNYUAN", "https://api.hunyuan.cloud.tencent.com/v1",
                "hunyuan-pro", "https://console.cloud.tencent.com/hunyuan/api-key",
                List.of("hunyuan-pro", "hunyuan-standard", "hunyuan-lite"), null),
            t("xfyun-spark", "讯飞星火", "XFYUN_SPARK", "https://spark-api-open.xf-yun.com/v1",
                "4.0Ultra", "https://console.xfyun.cn/services/bm4",
                List.of("4.0Ultra", "generalv3.5", "generalv3"), null),
            t("custom", "自定义厂商", "CUSTOM", "",
                "", null, List.of(), "地址与模型全部手填")
        );
    }

    private static DcsAiProviderTemplateVo t(String id, String label, String provider, String baseUrl,
                                             String defaultModel, String consoleUrl, List<String> models,
                                             String note) {
        return new DcsAiProviderTemplateVo(id, label, provider, CHAT, baseUrl, defaultModel,
            consoleUrl, models, note);
    }
}
