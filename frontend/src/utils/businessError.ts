export const CAPTCHA_CHALLENGE_REQUIRED_CODE = 4601;

export class BusinessError<T = unknown> extends Error {
  readonly code: number;
  readonly data?: T;

  constructor(code: number, message: string, data?: T) {
    super(message);
    this.name = 'BusinessError';
    this.code = code;
    this.data = data;
  }
}

export const getBusinessErrorCode = (error: unknown): number | undefined => {
  const candidates = [(error as any)?.code, (error as any)?.response?.data?.code];
  for (const candidate of candidates) {
    const normalized = Number(candidate);
    if (Number.isFinite(normalized)) return normalized;
  }
  return undefined;
};

export const isBusinessErrorCode = (error: unknown, expectedCode: number): boolean => getBusinessErrorCode(error) === expectedCode;
