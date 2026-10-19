/**
 * 敏感词展示掩码工具(2026-08-23 拍板: 列表掩码 / 详情明文)。
 *
 * <p>用途: 事件流水"详情"列与敏感词库"词条"列在<b>列表态</b>默认打码(防肩窥/截图外泄),
 * 点开详情弹窗/编辑抽屉才显示原文。后端接口仍返回原文——内部管理页+权限已控,
 * 网络层全掩码属过度设计(设计稿已留档此取舍)。</p>
 */

/** 单词掩码: 词长≥3 显示首字+**, ≤2 全 *(短词保留过多信息无意义)。 */
export const maskToken = (word: string): string => (word.length >= 3 ? `${word[0]}**` : '*'.repeat(Math.max(word.length, 2)));

/**
 * 事件详情文本的列表态掩码, 兼容三种真实格式:
 * ① "命中敏感词: [词A][词B]" 中括号词条逐个打码;
 * ② "命中敏感词: 词" 冒号后缀单词条(库内实测格式)整体打码;
 * ③ "内容机检:命中X词[词面] 字段[路径]"(风控2.0) 只打码词面, 字段路径非敏感保留可读。
 * 其余文字原样保留; 无匹配文本原样返回。
 */
export const maskSensitiveDetail = (detail: string): string => {
  if (detail.startsWith('内容机检:')) {
    return detail.replace(/(命中(?:BLOCK|REVIEW)词\[)([^\]]+)(\])/, (_m, pre: string, w: string, post: string) => pre + maskToken(w) + post);
  }
  return (
    detail
      .replace(/\[([^\]]+)\]/g, (_m, w: string) => `[${maskToken(w)}]`)
      // 词条部分限定不含中括号, 避免把①已打码的中括号结果再次吞掉
      .replace(/(命中敏感词[:：]\s*)([^\[\]\s]+)(\s*$)/, (_m, p: string, w: string, tail: string) => p + maskToken(w) + tail)
  );
};
