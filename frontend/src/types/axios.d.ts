export {};
declare module 'axios' {
  interface AxiosResponse<T = any> {
    code: number;
    msg: string;
    rows: T;
    total: number;
  }
}
declare module 'axios' {
  interface AxiosRequestConfig {
    /** 业务批3-②(#13)：请求级自定义开关——custom.silent=true 走 request.ts 响应拦截器静默通道
     *  （轮询场景不弹错不弹重登框，错误只 reject 交调用方处置） */
    custom?: { silent?: boolean };
  }
}
