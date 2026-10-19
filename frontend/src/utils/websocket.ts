/**
 * WebSocket 接入 (增强版 - 集成 DCS 聊天模块)
 *
 * 原 RuoYi 版本: 所有消息都直接进通知中心.
 * 增强版: 先按 WsEnvelope 协议解析,如果是聊天事件转给 chatStore,
 *         否则保留原通知逻辑。
 *
 * 路径: frontend/src/utils/websocket.ts
 */

import { getToken } from '@/utils/auth';
import { ElNotification } from 'element-plus';
import { useNoticeStore } from '@/store/modules/notice';
// 批5: 群聊模块退役, chat envelope 分发移除

// 初始化socket
export const initWebSocket = (url: any) => {
  if (import.meta.env.VITE_APP_WEBSOCKET === 'false') {
    return;
  }
  url = url + '?Authorization=Bearer ' + getToken() + '&clientid=' + import.meta.env.VITE_APP_CLIENT_ID;
  useWebSocket(url, {
    autoReconnect: {
      retries: 3,
      delay: 1000,
      onFailed() {
        console.log('websocket重连失败');
      }
    },
    heartbeat: {
      message: JSON.stringify({ type: 'ping' }),
      interval: 10000,
      pongTimeout: 2000
    },
    onConnected() {
      console.log('websocket已经连接');
    },
    onDisconnected() {
      console.log('websocket已经断开');
    },
    onMessage: (_, e) => {
      // 心跳忽略
      if (typeof e.data === 'string' && e.data.indexOf('ping') > 0) {
        return;
      }

      // 尝试解析 WsEnvelope
      let envelope: any = null;
      try {
        envelope = JSON.parse(e.data);
      } catch {
        // 非 JSON 数据,走原通知逻辑
      }

      // 1) 是聊天事件 → 交给 chatStore
      const ev = envelope?.event;
      if (typeof ev === 'string' && (ev.startsWith('CHAT_') || ev.startsWith('CONV_'))) {
        try {
          // chat envelope 已随群聊退役(批5)
        } catch (err) {
          console.error('[ws] chat dispatch error', err);
        }
        return;
      }

      // 2) 非聊天事件 - 走原通知逻辑(保持向后兼容)
      const noticeStore = useNoticeStore();
      noticeStore.addNotice({
        message: e.data,
        read: false,
        time: new Date().toLocaleString()
      });
      noticeStore.refreshUnreadCount();
      noticeStore.fetchRecent();
      ElNotification({
        title: '消息',
        message: typeof e.data === 'string' ? e.data : '收到一条新消息',
        type: 'success',
        duration: 3000
      });
    }
  });
};
