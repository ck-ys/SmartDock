<template>
  <div class="app-container announcement-page">
    <!-- 列表 -->
    <template v-if="!active">
      <header class="an-head">
        <div class="an-head__main">
          <p class="an-eyebrow">系统支持</p>
          <h1 class="an-title">公告</h1>
          <p class="an-sub">版本发布、维护安排与使用提醒都会在这里说明；点开卡片可查看全文。</p>
        </div>
        <div class="an-stats">
          <div class="an-stat">
            <b>{{ total }}</b>
            <span>当前筛选</span>
          </div>
          <div class="an-stat an-stat--unread">
            <b>{{ unreadCount }}</b>
            <span>未读</span>
          </div>
        </div>
      </header>

      <div class="an-toolbar">
        <el-radio-group v-model="query.readFlag" size="small" @change="reload">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="0">未读</el-radio-button>
          <el-radio-button value="1">已读</el-radio-button>
        </el-radio-group>
        <el-button text :loading="loading" @click="reload">刷新</el-button>
      </div>

      <div v-loading="loading" class="an-grid">
        <article
          v-for="item in rows"
          :key="item.noticeId"
          class="an-card"
          :class="{ 'is-unread': isUnread(item), 'is-top': isTop(item) }"
          role="button"
          tabindex="0"
          @click="open(item)"
          @keydown.enter.prevent="open(item)"
          @keydown.space.prevent="open(item)"
        >
          <div class="an-card__flags">
            <span v-if="isTop(item)" class="an-pin">置顶</span>
            <span class="an-kind">{{ typeLabel(item.noticeType) }}</span>
            <span v-if="isUnread(item)" class="an-unread">未读</span>
          </div>
          <h2 class="an-card__title">{{ item.noticeTitle }}</h2>
          <p class="an-card__excerpt">{{ excerpt(item.noticeContent) }}</p>
          <footer class="an-card__foot">
            <time :datetime="item.publishTime || item.createTime">{{ formatDate(item.publishTime || item.createTime) }}</time>
            <span class="an-more">查看全文<span aria-hidden="true">→</span></span>
          </footer>
        </article>

        <el-empty v-if="!loading && rows.length === 0" class="an-empty" :description="emptyText" :image-size="72" />
      </div>

      <pagination
        v-show="total > 0"
        v-model:page="query.pageNum"
        v-model:limit="query.pageSize"
        :total="total"
        @pagination="load"
      />
    </template>

    <!-- 文章页（同一路由内切换，返回不丢筛选与页码） -->
    <article v-else-if="active" class="an-article">
      <button type="button" class="an-back" @click="closeArticle">
        <span aria-hidden="true">←</span> 返回公告列表
      </button>

      <header class="an-article__head">
        <div class="an-card__flags">
          <span v-if="isTop(active)" class="an-pin">置顶</span>
          <span class="an-kind">{{ typeLabel(active.noticeType) }}</span>
        </div>
        <h1 class="an-article__title">{{ active.noticeTitle }}</h1>
        <p class="an-article__meta">
          <span>发布于 {{ formatDate(active.publishTime || active.createTime) }}</span>
          <span v-if="active.expireTime">· 有效期至 {{ formatDate(active.expireTime) }}</span>
        </p>
      </header>

      <!-- eslint-disable-next-line vue/no-v-html —— 正文为后台富文本，后端已过 HtmlUtil 白名单 -->
      <div class="an-article__body" v-html="active.noticeContent"></div>

      <footer class="an-article__foot">
        <span>读完了</span>
        <el-button size="small" @click="closeArticle">返回列表</el-button>
      </footer>
    </article>
  </div>
</template>

<script setup name="DcsAnnouncement" lang="ts">
import { listAnnouncements, markAnnouncementRead } from '@/api/dcs/announcement';
import type { AnnouncementRow } from '@/api/dcs/announcement/types';
import { useAnnouncementStore } from '@/store/modules/announcement';

const announcementStore = useAnnouncementStore();

const loading = ref(false);
const rows = ref<AnnouncementRow[]>([]);
const total = ref(0);
const active = ref<AnnouncementRow | null>(null);
const query = reactive({ pageNum: 1, pageSize: 8, readFlag: '' });

const unreadCount = computed(() => announcementStore.state.unreadCount);
const emptyText = computed(() => (query.readFlag === '0' ? '没有未读公告' : '暂无公告'));

const isTop = (row: AnnouncementRow) => row.isTop === '1';
const isUnread = (row: AnnouncementRow) => row.readFlag === '0';
const typeLabel = (type?: string) => (type === '2' ? '公告' : '通知');

const formatDate = (value?: string) => {
  if (!value) {
    return '—';
  }
  const [date, time] = value.replace('T', ' ').split(' ');
  if (!date) {
    return value;
  }
  const [y, m, d] = date.split('-');
  return time ? `${y}年${Number(m)}月${Number(d)}日 ${time.slice(0, 5)}` : `${y}年${Number(m)}月${Number(d)}日`;
};

/** 后台是富文本，摘要按纯文本展示（正文在文章页按 HTML 渲染，后端已过白名单）。 */
const stripHtml = (html: string) => html.replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim();

const excerpt = (content?: string) => {
  const text = stripHtml(content || '');
  return text || '暂无正文';
};

let loadSeq = 0;

const load = async () => {
  const current = ++loadSeq;
  loading.value = true;
  try {
    const res: any = await listAnnouncements({ ...query, readFlag: query.readFlag || undefined });
    if (current !== loadSeq) {
      return; // 快速切筛选时旧响应不得覆盖新结果
    }
    rows.value = res.rows || [];
    total.value = Number(res.total || 0);
    void announcementStore.refreshUnreadCount();
  } finally {
    if (current === loadSeq) {
      loading.value = false;
    }
  }
};

const reload = () => {
  query.pageNum = 1;
  load();
};

const open = async (row: AnnouncementRow) => {
  active.value = row;
  window.scrollTo({ top: 0, behavior: 'smooth' });
  if (!isUnread(row)) {
    return;
  }
  try {
    await markAnnouncementRead(row.noticeId);
    row.readFlag = '1';
    announcementStore.consumeOne();
    if (query.readFlag === '0') {
      // 未读筛选下读掉的就地从列表移除，返回时不会看到已读项
      rows.value = rows.value.filter((item) => item.noticeId !== row.noticeId);
      total.value = Math.max(0, total.value - 1);
    }
  } catch {
    // 标记失败不回滚阅读，下次进入列表会重新显示未读
  }
};

const closeArticle = () => {
  active.value = null;
  window.scrollTo({ top: 0, behavior: 'smooth' });
};

onMounted(load);
</script>

<style lang="scss" scoped>
.announcement-page {
  max-width: 1080px;
  margin: 0 auto;
}

/* ---------- 页头 ---------- */
.an-head {
  display: flex;
  align-items: flex-end;
  gap: 24px;
  padding: 22px 24px;
  margin-bottom: 16px;
  border: 1px solid var(--dcs-border-color-lighter);
  border-radius: 14px;
  background:
    radial-gradient(120% 140% at 0% 0%, var(--el-color-primary-light-9) 0%, transparent 58%),
    var(--el-bg-color);

  &__main {
    min-width: 0;
    flex: 1;
  }

  .an-eyebrow {
    margin: 0 0 4px;
    font-size: 12px;
    letter-spacing: 0.12em;
    color: var(--el-color-primary);
  }

  .an-title {
    margin: 0;
    font-size: 24px;
    line-height: 1.35;
    font-weight: 650;
    color: var(--dcs-text-primary);
  }

  .an-sub {
    margin: 8px 0 0;
    max-width: 640px;
    font-size: 14px;
    line-height: 1.7;
    color: var(--dcs-text-secondary);
  }
}

.an-stats {
  display: flex;
  flex: none;
  gap: 10px;
}

.an-stat {
  min-width: 88px;
  padding: 10px 14px;
  border-radius: 10px;
  background: var(--el-fill-color-lighter);
  text-align: center;

  b {
    display: block;
    font-size: 22px;
    line-height: 1.2;
    font-variant-numeric: tabular-nums;
    color: var(--dcs-text-primary);
  }

  span {
    display: block;
    margin-top: 2px;
    font-size: 12px;
    color: var(--dcs-text-placeholder);
  }

  &--unread b {
    color: var(--el-color-primary);
  }
}

/* ---------- 工具栏 ---------- */
.an-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

/* ---------- 卡片流 ---------- */
.an-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  min-height: 160px;
}

.an-empty {
  grid-column: 1 / -1;
}

.an-card {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 176px;
  padding: 18px 18px 14px 20px;
  border: 1px solid var(--dcs-border-color-lighter);
  border-radius: 12px;
  background: var(--el-bg-color);
  cursor: pointer;
  outline: none;
  transition:
    border-color 0.18s ease,
    box-shadow 0.18s ease,
    transform 0.18s ease;

  /* 未读：左侧主色标识条 */
  &::before {
    content: '';
    position: absolute;
    left: -1px;
    top: 14px;
    bottom: 14px;
    width: 3px;
    border-radius: 0 3px 3px 0;
    background: transparent;
  }

  &.is-unread::before {
    background: var(--el-color-primary);
  }

  &:hover,
  &:focus-visible {
    border-color: var(--el-color-primary-light-5);
    box-shadow: 0 8px 20px rgb(0 0 0 / 6%);
    transform: translateY(-1px);
  }

  &:focus-visible {
    box-shadow:
      0 0 0 3px var(--el-color-primary-light-7),
      0 8px 20px rgb(0 0 0 / 6%);
  }

  &__flags {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 6px;
    margin-bottom: 10px;
  }

  &__title {
    margin: 0 0 8px;
    font-size: 16.5px;
    line-height: 1.5;
    font-weight: 600;
    color: var(--dcs-text-primary);
    overflow-wrap: anywhere;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }

  &__excerpt {
    margin: 0;
    flex: 1;
    font-size: 13.5px;
    line-height: 1.7;
    color: var(--dcs-text-secondary);
    overflow-wrap: anywhere;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }

  &__foot {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-top: 14px;
    padding-top: 10px;
    border-top: 1px dashed var(--dcs-border-color-lighter);
    font-size: 12.5px;
    color: var(--dcs-text-placeholder);

    time {
      font-variant-numeric: tabular-nums;
    }
  }

  .an-more {
    display: inline-flex;
    gap: 3px;
    color: var(--el-color-primary);
    opacity: 0;
    transition: opacity 0.18s ease;
  }

  &:hover .an-more,
  &:focus-visible .an-more {
    opacity: 1;
  }
}

.an-pin,
.an-kind,
.an-unread {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border-radius: 999px;
  font-size: 11.5px;
  line-height: 1;
  white-space: nowrap;
}

.an-pin {
  background: var(--el-color-danger-light-9);
  border: 1px solid var(--el-color-danger-light-7);
  color: var(--el-color-danger);
}

.an-kind {
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
  color: var(--dcs-text-secondary);
}

.an-unread {
  background: var(--el-color-primary-light-9);
  border: 1px solid var(--el-color-primary-light-7);
  color: var(--el-color-primary);
}

/* ---------- 文章页 ---------- */
.an-article {
  max-width: 820px;
  margin: 0 auto;
  padding: 24px 28px 20px;
  border: 1px solid var(--dcs-border-color-lighter);
  border-radius: 14px;
  background: var(--el-bg-color);
}

.an-back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  margin: -6px 0 16px -10px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--dcs-text-secondary);
  font-size: 13.5px;
  cursor: pointer;

  &:hover {
    background: var(--el-fill-color-light);
    color: var(--el-color-primary);
  }
}

.an-article__head {
  padding-bottom: 16px;
  border-bottom: 1px solid var(--dcs-border-color-lighter);
}

.an-article__title {
  margin: 4px 0 10px;
  font-size: 24px;
  line-height: 1.45;
  font-weight: 650;
  color: var(--dcs-text-primary);
  overflow-wrap: anywhere;
}

.an-article__meta {
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 12.5px;
  color: var(--dcs-text-placeholder);
  font-variant-numeric: tabular-nums;
}

.an-article__body {
  padding: 20px 0 8px;
  font-size: 15px;
  line-height: 1.9;
  color: var(--dcs-text-regular);
  white-space: pre-wrap;
  overflow-wrap: anywhere;

  :deep(p) {
    margin: 0 0 12px;
  }

  :deep(img) {
    max-width: 100%;
    height: auto;
    border-radius: 8px;
  }

  :deep(ul),
  :deep(ol) {
    padding-left: 22px;
    margin: 0 0 12px;
  }

  :deep(h1),
  :deep(h2),
  :deep(h3) {
    margin: 18px 0 10px;
    font-size: 1.1em;
    color: var(--dcs-text-primary);
  }

  :deep(a) {
    color: var(--el-color-primary);
  }
}

.an-article__foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  padding-top: 14px;
  border-top: 1px dashed var(--dcs-border-color-lighter);
  font-size: 12.5px;
  color: var(--dcs-text-placeholder);
}

/* ---------- 响应式 ---------- */
@media (max-width: 992px) {
  .an-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .an-card {
    min-height: 0;

    .an-more {
      opacity: 1;
    }
  }
}

@media (max-width: 640px) {
  .announcement-page {
    padding-left: 12px;
    padding-right: 12px;
  }

  .an-head {
    flex-direction: column;
    align-items: stretch;
    gap: 14px;
    padding: 16px;

    .an-title {
      font-size: 21px;
    }

    .an-stats {
      justify-content: flex-start;
    }
  }

  .an-stat {
    flex: 1;
    min-width: 0;
  }

  .an-article {
    padding: 16px 15px;
    border-radius: 12px;
  }

  .an-article__title {
    font-size: 20px;
  }

  .an-article__body {
    font-size: 14.5px;
    line-height: 1.85;
  }
}
</style>
