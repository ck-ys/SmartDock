// 批5: 群聊退役, 原类型本地化(字段口径保持兼容)
export interface SendMessageRequest { content: string; msgId?: string; [k: string]: unknown }
import type { CaptchaVerificationResult } from '@/api/dcs/risk/types';
import { CAPTCHA_CHALLENGE_REQUIRED_CODE, isBusinessErrorCode } from '@/utils/businessError';

export { CAPTCHA_CHALLENGE_REQUIRED_CODE };

export const captchaActionForChat = (convId: number | string): string => `chat:${convId}`;

export const ensureChatClientMsgId = (payload: SendMessageRequest): SendMessageRequest =>
  payload.clientMsgId
    ? payload
    : {
        ...payload,
        clientMsgId: `cli_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
      };

export const withCaptchaPass = (payload: SendMessageRequest, captchaPassToken: string): SendMessageRequest => ({
  ...payload,
  captchaPassToken
});

export const isCaptchaChallengeRequired = (error: unknown): boolean => isBusinessErrorCode(error, CAPTCHA_CHALLENGE_REQUIRED_CODE);

export const resolveCaptchaPassToken = (result?: CaptchaVerificationResult): string | undefined =>
  result?.verified === true && result.needOtp === false && result.captchaPassToken ? result.captchaPassToken : undefined;
