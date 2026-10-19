import axios, { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { useUserStore } from '@/store/modules/user';
import { getToken } from '@/utils/auth';
import { tansParams, blobValidate } from '@/utils/ruoyi';
import cache from '@/plugins/cache';
import { HttpStatus } from '@/enums/RespEnum';
import { errorCode } from '@/utils/errorCode';
import { LoadingInstance } from 'element-plus/es/components/loading/src/loading';
import FileSaver from 'file-saver';
import { getLanguage } from '@/lang';
import { encryptBase64, encryptWithAes, generateAesKey, decryptWithAes, decryptBase64 } from '@/utils/crypto';
import { encrypt, decrypt } from '@/utils/jsencrypt';
import router from '@/router';
import { BusinessError, CAPTCHA_CHALLENGE_REQUIRED_CODE } from '@/utils/businessError';

const encryptHeader = 'encrypt-key';
let downloadLoadingInstance: LoadingInstance;
// 是否显示重新登录
export const isRelogin = { show: false };
export const globalHeaders = () => {
  return {
    Authorization: 'Bearer ' + getToken(),
    clientid: import.meta.env.VITE_APP_CLIENT_ID
  };
};

axios.defaults.headers['Content-Type'] = 'application/json;charset=utf-8';
axios.defaults.headers['clientid'] = import.meta.env.VITE_APP_CLIENT_ID;
// 创建 axios 实例
const service = axios.create({
  baseURL: import.meta.env.VITE_APP_BASE_API,
  timeout: 50000,
  transitional: {
    // 超时错误更明确
    clarifyTimeoutError: true
  }
});

// 请求拦截器
service.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    // 对应国际化资源文件后缀
    config.headers['Content-Language'] = getLanguage();

    const isToken = config.headers?.isToken === false;
    // 是否需要防止数据重复提交
    const isRepeatSubmit = config.headers?.repeatSubmit === false;
    // 是否需要加密
    const isEncrypt = config.headers?.isEncrypt === 'true';

    if (getToken() && !isToken) {
      config.headers['Authorization'] = 'Bearer ' + getToken(); // 让每个请求携带自定义token 请根据实际情况自行修改
    }
    // get请求映射params参数
    if (config.method === 'get' && config.params) {
      let url = config.url + '?' + tansParams(config.params);
      url = url.slice(0, -1);
      config.params = {};
      config.url = url;
    }

    if (!isRepeatSubmit && (config.method === 'post' || config.method === 'put')) {
      const requestObj = {
        url: config.url,
        data: typeof config.data === 'object' ? JSON.stringify(config.data) : config.data,
        time: new Date().getTime()
      };
      const sessionObj = cache.session.getJSON('sessionObj');
      if (sessionObj === undefined || sessionObj === null || sessionObj === '') {
        cache.session.setJSON('sessionObj', requestObj);
      } else {
        const s_url = sessionObj.url; // 请求地址
        const s_data = sessionObj.data; // 请求数据
        const s_time = sessionObj.time; // 请求时间
        const interval = 500; // 间隔时间(ms)，小于此时间视为重复提交
        if (s_data === requestObj.data && requestObj.time - s_time < interval && s_url === requestObj.url) {
          const message = '数据正在处理，请勿重复提交';
          console.warn(`[${s_url}]: ` + message);
          return Promise.reject(new Error(message));
        } else {
          cache.session.setJSON('sessionObj', requestObj);
        }
      }
    }
    if (import.meta.env.VITE_APP_ENCRYPT === 'true') {
      // 当开启参数加密
      if (isEncrypt && (config.method === 'post' || config.method === 'put')) {
        // 生成一个 AES 密钥
        const aesKey = generateAesKey();
        config.headers[encryptHeader] = encrypt(encryptBase64(aesKey));
        config.data = typeof config.data === 'object' ? encryptWithAes(JSON.stringify(config.data), aesKey) : encryptWithAes(config.data, aesKey);
      }
    }
    // FormData数据去请求头Content-Type
    if (config.data instanceof FormData) {
      delete config.headers['Content-Type'];
    }
    return config;
  },
  (error: any) => {
    return Promise.reject(error);
  }
);

// 响应拦截器
service.interceptors.response.use(
  async (res: AxiosResponse) => {
    if (import.meta.env.VITE_APP_ENCRYPT === 'true') {
      // 加密后的 AES 秘钥
      const keyStr = res.headers[encryptHeader];
      // 加密
      if (keyStr != null && keyStr != '') {
        const data = res.data;
        // 请求体 AES 解密
        const base64Str = decrypt(keyStr);
        // base64 解码 得到请求头的 AES 秘钥
        const aesKey = decryptBase64(base64Str.toString());
        // aesKey 解码 data
        const decryptData = decryptWithAes(data, aesKey);
        // 将结果 (得到的是 JSON 字符串) 转为 JSON
        res.data = JSON.parse(decryptData);
      }
    }
    // 未设置状态码则默认成功状态
    const code = res.data.code || HttpStatus.SUCCESS;
    // 业务批3-②(#13)：静默通道——请求 config 带 custom:{silent:true} 时（如余额轮询），
    // 所有弹错/重登框短路，错误只走 reject 交给调用方自行处置（轮询方负责停表防风暴）
    const silent = (res.config as any)?.custom?.silent === true;
    // 获取错误信息
    // F3(2026-09-16): 后端原话优先——此前字典文案(如 403"当前操作没有权限")盖掉后端
    // 定制原因(如"您不是该链路的上级,无权催办"), 用户不知道被拦的真实原因;
    // 后端未给 msg 时仍回落字典/默认文案
    const msg = res.data.msg || errorCode[code] || errorCode['default'];
    // 二进制数据则直接返回
    if (res.request.responseType === 'blob' || res.request.responseType === 'arraybuffer') {
      // 六审回修（k3 P2）：后端报错时 body 是 JSON 不是文件——原样返回会把错误 JSON 当
      // 文件下载成损坏的 .docx/.xlsx 且无任何提示；content-type 为 json 时解析出 msg 走
      // 统一错误道（AI 汇报导出失败"导出失败请稍后再试"双弹的同族根因，另一处在调用方）
      const ctype = String(res.headers?.['content-type'] ?? '');
      if (ctype.includes('application/json') && res.data instanceof Blob) {
        try {
          const rsp = JSON.parse(await res.data.text());
          const errMsg = rsp?.msg || errorCode[rsp?.code] || errorCode['default'];
          if (!silent) {
            ElMessage.error(errMsg);
          }
          return Promise.reject(new BusinessError(rsp?.code || 500, errMsg, rsp?.data));
        } catch {
          /* 非 JSON 文本（怪异响应体）按正常 blob 返回，交给调用方 */
        }
      }
      return res.data;
    }
    if (code === 401) {
      // 静默请求（轮询）不弹重登框——调用方 catch 到 401 自行停表
      if (silent) {
        return Promise.reject(new BusinessError(401, msg, res.data.data));
      }
      // prettier-ignore
      if (!isRelogin.show) {
        isRelogin.show = true;
        ElMessageBox.confirm('登录状态已过期，您可以继续留在该页面，或者重新登录', '系统提示', {
          confirmButtonText: '重新登录',
          cancelButtonText: '取消',
          type: 'warning'
        }).then(() => {
          isRelogin.show = false;
          useUserStore().logout().then(() => {
            // 换账号弹回无权页面修复(2026-09-29): 掉线重登保留"回原页"便利, 但改整页跳转清内存残留
            // (页签卡片/上个账号的动态路由), 并给 redirect 盖归属章(401 发生时的登录者)——
            // 换人登录后由路由守卫按章拦截跳首页, 同人重登回原页不受影响
            try {
              // 注意: 此处 uid 依赖 user.ts 的 logout() 只清 token/roles/permissions 而不清 userId——
              // 保留的 userId 恰好等于"401 发生时的登录者", 归属章才有效; 勿在 logout 里顺手清 userId,
              // 否则章盖不出来, 换人保护退化为无章(深链语义)
              const uid = useUserStore().userId;
              if (uid) sessionStorage.setItem('dcs:login-redirect-owner', String(uid));
            } catch (e) {
              // storage 不可用时退化为无章(等同深链语义), 不阻断退出流程
            }
            location.href = import.meta.env.VITE_APP_CONTEXT_PATH + 'login?redirect=' + encodeURIComponent(router.currentRoute.value.fullPath || '/');
          });
        }).catch(() => {
          isRelogin.show = false;
        });
      }
      return Promise.reject('无效的会话，或者会话已过期，请重新登录。');
    } else if (code === HttpStatus.SERVER_ERROR) {
      if (!silent) {
        ElMessage({ message: msg, type: 'error' });
      }
      return Promise.reject(new BusinessError(code, msg, res.data.data));
    } else if (code === HttpStatus.WARN) {
      if (!silent) {
        ElMessage({ message: msg, type: 'warning' });
      }
      return Promise.reject(new BusinessError(code, msg, res.data.data));
    } else if (code !== HttpStatus.SUCCESS) {
      if (code !== CAPTCHA_CHALLENGE_REQUIRED_CODE && !silent) {
        ElNotification.error({ title: msg });
      }
      return Promise.reject(new BusinessError(code, msg, res.data.data));
    } else {
      return Promise.resolve(res.data);
    }
  },
  (error: any) => {
    let { message } = error;
    // F3(2026-09-16): HTTP 层错误若带后端响应体, 优先透传后端 msg(原话), 再回落网络类文案
    if (error.response?.data?.msg) {
      message = error.response.data.msg;
    } else if (message == 'Network Error') {
      message = '后端接口连接异常';
    } else if (message.includes('timeout')) {
      message = '系统接口请求超时';
    } else if (message.includes('Request failed with status code')) {
      message = '系统接口' + message.substr(message.length - 3) + '异常';
    }
    // 静默请求（轮询）不弹错——调用方自行处置
    if ((error.config as any)?.custom?.silent !== true) {
      ElMessage({ message: message, type: 'error', duration: 5 * 1000 });
    }
    return Promise.reject(error);
  }
);
// 通用下载方法
export function download(url: string, params: any, fileName: string) {
  downloadLoadingInstance = ElLoading.service({ text: '正在下载数据，请稍候', background: 'rgba(0, 0, 0, 0.7)' });
  // prettier-ignore
  return service.post(url, params, {
      transformRequest: [
        (params: any) => {
          return tansParams(params);
        }
      ],
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      responseType: 'blob'
    }).then(async (resp: any) => {
      const isLogin = blobValidate(resp);
      if (isLogin) {
        const blob = new Blob([resp]);
        FileSaver.saveAs(blob, fileName);
      } else {
        const blob = new Blob([resp]);
        const resText = await blob.text();
        const rspObj = JSON.parse(resText);
        const errMsg = errorCode[rspObj.code] || rspObj.msg || errorCode['default'];
        ElMessage.error(errMsg);
      }
      downloadLoadingInstance.close();
    }).catch((r: any) => {
      console.error(r);
      ElMessage.error('下载文件出现错误，请联系管理员！');
      downloadLoadingInstance.close();
    });
}
// 导出 axios 实例
export default service;
