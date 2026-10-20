package com.smartdock.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import com.smartdock.domain.vo.DcsAiProviderPresetVo;

import java.util.Arrays;
import java.util.List;

/**
 * OpenAI-compatible AI provider 预置。
 *
 * @author smartdock
 */
@Getter
@AllArgsConstructor
public enum DcsAiProvider {

    /** OpenAI 官方 */
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4.1-mini"),
    /** DeepSeek 深度求索 */
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
    /** 通义千问(阿里云) */
    QWEN("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
    /** 智谱 GLM(智谱 AI/Z.AI) */
    ZHIPU("智谱/Z.AI", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
    /** Kimi(Moonshot) */
    KIMI("Kimi", "https://api.moonshot.ai/v1", "kimi-k2-0711-preview"),
    /** 百度千帆(文心一言) */
    BAIDU_QIANFAN("百度千帆", "https://qianfan.baidubce.com/v2", "ernie-4.0-8k-latest"),
    /** 腾讯混元 */
    TENCENT_HUNYUAN("腾讯混元", "https://api.hunyuan.cloud.tencent.com/v1", "hunyuan-pro"),
    /** 讯飞星火 */
    XFYUN_SPARK("讯飞星火", "https://spark-api-open.xf-yun.com/v1", "4.0Ultra"),
    /** MiniMax(国内站；2026-10-20 快刀批 Q9：api.minimax.cn/v1 为官方文档现行端点，api.minimaxi.com 为历史别名同样存活，国际站 api.minimax.io 走「自定义」自填；默认模型升 M3.1-Flash——能力规则与人民币价格种子见 baseline/02_reference.sql + migration) */
    MINIMAX("MiniMax", "https://api.minimax.cn/v1", "MiniMax-M3.1-Flash"),
    /** 硅基流动(聚合平台) */
    SILICONFLOW("硅基流动", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct"),
    /** OpenCode Go 订阅网关(月订阅,含限免免费模型;调用侧须带自报UA+x-opencode-session头,见 AssistantModelFactory) */
    OPENCODE("OpenCode Go", "https://opencode.ai/zen/go/v1", "longcat-2.5-preview-free"),
    /** 阶跃星辰 StepFun(批C C3;Step-5-Preview 600B/27B激活,1M 上下文,文+图+视频输入) */
    STEPFUN("阶跃星辰", "https://api.stepfun.com/v1", "step-5-preview"),
    /** OpenRouter 聚合网关(业务批4 Q11:OpenAI 兼容端点 /chat/completions 直通;余额走专有端点 /key,见 ProbeClient) */
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "deepseek/deepseek-flash-latest"),
    /** 自定义(走用户自填 baseUrl/模型名) */
    CUSTOM("自定义", "", "");

    private final String label;
    private final String baseUrl;
    private final String modelName;

    public static List<DcsAiProviderPresetVo> presets() {
        return Arrays.stream(values())
            .map(p -> new DcsAiProviderPresetVo(p.name(), p.getLabel(), p.getBaseUrl(), p.getModelName()))
            .toList();
    }

    public static boolean supported(String value) {
        return Arrays.stream(values()).anyMatch(p -> p.name().equals(value));
    }
}
