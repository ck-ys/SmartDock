<template>
  <div v-loading="noticeStore.state.loading" class="layout-navbars-breadcrumb-user-news">
    <div class="head-box">
      <div class="head-box-title">消息通知</div>
      <div class="head-box-btn" @click="handleReadAll">全部已读</div>
    </div>
    <div class="content-box">
      <template v-if="newsList.length > 0">
        <div v-for="item in newsList" :key="getNoticeKey(item)" class="content-box-item" @click="onNewsClick(item)">
          <div class="item-content">
            <div class="content-box-title">{{ getNoticeTitle(item) }}</div>
            <div class="content-box-msg">{{ getNoticeContent(item) }}</div>
            <div class="content-box-time">{{ getNoticeTime(item) }}</div>
          </div>
          <el-tag v-if="isRead(item)" type="success" size="small" class="read">已读</el-tag>
          <el-tag v-else type="danger" size="small" class="read">未读</el-tag>
        </div>
      </template>
      <el-empty v-else description="暂无消息" />
    </div>
    <div class="foot-box foot-announcement" v-hasPermi="['dcs:announcement:list']" @click="goAnnouncement">
      <svg-icon icon-class="notification" class="foot-icon" />
      <span>公告</span>
      <span v-if="announcementUnread > 0" class="foot-badge">{{ announcementUnread > 99 ? '99+' : announcementUnread }} 条未读</span>
      <span v-else class="foot-muted">全部已读</span>
      <span class="foot-arrow" aria-hidden="true">→</span>
    </div>
    <div v-if="newsList.length > 0" class="foot-box" @click="goNoticeCenter">查看全部消息</div>
  </div>
</template>

<script setup lang="ts" name="layoutBreadcrumbUserNews">
import { useNoticeStore, NoticeItem } from '@/store/modules/notice';
import { useAnnouncementStore } from '@/store/modules/announcement';
import { getNoticeContent, getNoticeKey, getNoticeTime, getNoticeTitle, isNoticeUnread, isPersistedNotice } from '@/store/modules/notice-helpers';

const router = useRouter();
const noticeStore = useNoticeStore();
const announcementStore = useAnnouncementStore();
const newsList = computed(() => noticeStore.state.notices);
const announcementUnread = computed(() => announcementStore.state.unreadCount);

const goAnnouncement = () => {
  announcementStore.refreshUnreadCount();
  router.push('/support/announcement');
};

const isRead = (item: NoticeItem) => !isNoticeUnread(item);

const handleReadAll = async () => {
  await noticeStore.readAll();
};

const onNewsClick = async (item: NoticeItem) => {
  await noticeStore.readNotice(item);
  if (isPersistedNotice(item) && item.refUrl) {
    router.push(item.refUrl);
  }
};

const goNoticeCenter = () => {
  router.push('/dcs/notice');
};

onMounted(() => {
  noticeStore.fetchRecent();
});
</script>

<style lang="scss" scoped>
.layout-navbars-breadcrumb-user-news {
  .head-box {
    display: flex;
    border-bottom: 1px solid var(--el-border-color-lighter);
    box-sizing: border-box;
    color: var(--el-text-color-primary);
    justify-content: space-between;
    height: 35px;
    align-items: center;
    .head-box-btn {
      color: var(--el-color-primary);
      font-size: 13px;
      cursor: pointer;
      opacity: 0.8;
      &:hover {
        opacity: 1;
      }
    }
  }
  .content-box {
    height: 300px;
    overflow: auto;
    font-size: 13px;
    .content-box-item {
      display: flex;
      gap: 8px;
      padding-top: 12px;
      cursor: pointer;
      &:last-of-type {
        padding-bottom: 12px;
      }
      &:hover {
        color: var(--el-color-primary);
      }
      .content-box-title {
        color: var(--el-text-color-primary);
        font-weight: 600;
      }
      .content-box-msg {
        color: var(--el-text-color-regular);
        margin-top: 5px;
        margin-bottom: 5px;
        line-height: 1.4;
      }
      .content-box-time {
        color: var(--el-text-color-secondary);
      }
      .item-content {
        width: 100%;
        display: flex;
        flex-direction: column;
        min-width: 0;
      }
      .read {
        flex: none;
      }
    }
  }
  .foot-box {
    height: 35px;
    color: var(--el-color-primary);
    font-size: 13px;
    cursor: pointer;
    opacity: 0.8;
    display: flex;
    align-items: center;
    justify-content: center;
    border-top: 1px solid var(--el-border-color-lighter);
    &:hover {
      opacity: 1;
    }
  }

  /* 公告入口：消息气泡底部的固定行（手机顶栏不再单放公告图标） */
  .foot-announcement {
    justify-content: flex-start;
    gap: 6px;
    padding: 0 4px;

    .foot-icon {
      font-size: 15px;
    }

    .foot-badge {
      padding: 0 6px;
      border-radius: 999px;
      background: var(--el-color-primary-light-9);
      color: var(--el-color-primary);
      font-size: 11.5px;
      line-height: 18px;
    }

    .foot-muted {
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }

    .foot-arrow {
      margin-left: auto;
      font-size: 13px;
    }
  }
  :deep(.el-empty__description p) {
    font-size: 13px;
  }
}
</style>
