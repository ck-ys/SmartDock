import { getToken } from '@/utils/auth';
import { ElNotification } from 'element-plus';
import { useNoticeStore } from '@/store/modules/notice';
// 批5: 群聊模块退役, chat envelope 分发移除
import { shouldDisplayDcsNotice } from '@/utils/sseNoticeDedup';

// 初始化
export const initSSE = (url: any) => {
  if (import.meta.env.VITE_APP_SSE === 'false') {
    return;
  }

  url = url + '?Authorization=Bearer ' + getToken() + '&clientid=' + import.meta.env.VITE_APP_CLIENT_ID;
  const { data, error } = useEventSource(url, [], {
    autoReconnect: {
      retries: 5,
      delay: 5000,
      onFailed() {
        console.log('Failed to connect after 5 retries');
      }
    }
  });

  watch(error, () => {
    console.log('SSE connection error:', error.value);
    error.value = null;
  });

  watch(data, () => {
    if (!data.value) return;

    // 先尝试解析为 DCS 聊天信封 (WsEnvelope: {event, data})
    // 聊天事件走 chatStore, 其余走通知中心(保持向后兼容)
    let envelope: any = null;
    try {
      envelope = JSON.parse(data.value);
    } catch {
      // 非 JSON 数据, 走原通知逻辑
    }
    const ev = envelope?.event;
    let handled = false;
    if (typeof ev === 'string' && (ev.startsWith('CHAT_') || ev.startsWith('CONV_') || ev === 'DCS_DING')) {
      try {
        // chat envelope 已随群聊退役(批5)
      } catch (err) {
        console.error('[sse] chat dispatch error', err);
      }
    }

    // DCS 业务通知 (催办/审核/转发等): 取 envelope.data 的 title/content 友好展示, 不再显示 raw JSON
    if (!handled && ev === 'DCS_NOTIFY') {
      const d = envelope?.data || {};
      if (!shouldDisplayDcsNotice(d)) {
        handled = true;
        data.value = null;
        return;
      }
      const noticeStore = useNoticeStore();
      noticeStore.addNotice({
        title: d.title,
        message: d.content,
        read: false,
        time: new Date().toLocaleString()
      });
      noticeStore.refreshUnreadCount();
      noticeStore.fetchRecent();
      ElNotification({
        title: d.title || '消息',
        message: d.content,
        type: 'warning',
        duration: 4000
      });
      handled = true;
    }

    if (!handled) {
      const noticeStore = useNoticeStore();
      // 兜底: 若是未识别的 JSON 信封, 优先取 data.title/content 友好降级, 避免显示原始 JSON
      const friendly = envelope?.data && typeof envelope.data === 'object' ? envelope.data : null;
      const msg = friendly?.content || friendly?.title || data.value;
      noticeStore.addNotice({
        title: friendly?.title,
        message: msg,
        read: false,
        time: new Date().toLocaleString()
      });
      noticeStore.refreshUnreadCount();
      noticeStore.fetchRecent();
      ElNotification({
        title: friendly?.title || '消息',
        message: msg,
        type: 'success',
        duration: 3000
      });
    }
    data.value = null;
  });
};
