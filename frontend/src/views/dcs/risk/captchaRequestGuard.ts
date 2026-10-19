export interface CaptchaRequestGuard {
  begin(): number;
  capture(): number;
  invalidate(): void;
  isCurrent(requestId: number): boolean;
}

export const createCaptchaRequestGuard = (): CaptchaRequestGuard => {
  let requestId = 0;

  return {
    begin() {
      requestId += 1;
      return requestId;
    },
    capture() {
      return requestId;
    },
    invalidate() {
      requestId += 1;
    },
    isCurrent(candidate) {
      return candidate === requestId;
    }
  };
};
