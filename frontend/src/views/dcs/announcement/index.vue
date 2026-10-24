<template>
  <div class="app-container announcement-page">
    <!-- 列表 -->
    <template v-if="!active">
      <support-page-head title="公告" description="版本发布、维护安排与使用提醒都会在这里说明；点开卡片可查看全文。">
        <template #aside>
          <div class="an-stats">
            <div class="an-stat">
              <b>{{ total }}</b>
              <span>{{ totalLabel }}</span>
            </div>
            <div v-if="query.readFlag !== '0'" class="an-stat an-stat--unread">
              <b>{{ unreadCount }}</b>
              <span>条未读</span>
            </div>
          </div>
        </template>
      </support-page-head>

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
          v-for="(item, index) in rows"
          :key="item.noticeId"
          class="an-card"
          :class="{
            'is-lead': index === 0 && rows.length > 1,
            'is-only': rows.length === 1
          }"
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

      <pagination v-show="total > 0" v-model:page="query.pageNum" v-model:limit="query.pageSize" :total="total" @pagination="load" />
    </template>

    <!-- 文章页（同一路由内切换，返回不丢筛选与页码） -->
    <article v-else-if="active" class="an-article">
      <div class="an-article__main">
        <button type="button" class="an-back" @click="closeArticle"><span aria-hidden="true">←</span> 返回公告列表</button>

        <header class="an-article__head">
          <div class="an-card__flags">
            <span v-if="isTop(active)" class="an-pin">置顶</span>
            <span class="an-kind">{{ typeLabel(active.noticeType) }}</span>
          </div>
          <h1 class="an-article__title">{{ active.noticeTitle }}</h1>
        </header>

        <!-- eslint-disable-next-line vue/no-v-html —— 正文为后台富文本，后端已过 HtmlUtil 白名单 -->
        <div class="an-article__body" v-html="active.noticeContent"></div>

        <footer class="an-article__foot">
          <span>读完了</span>
          <el-button size="small" @click="closeArticle">返回列表</el-button>
        </footer>
      </div>

      <aside class="an-article__side">
        <p class="an-side__label">公告信息</p>
        <dl class="an-side__list">
          <div>
            <dt>类型</dt>
            <dd>{{ typeLabel(active.noticeType) }}</dd>
          </div>
          <div>
            <dt>发布时间</dt>
            <dd>{{ formatDate(active.publishTime || active.createTime) }}</dd>
          </div>
          <div>
            <dt>阅读状态</dt>
            <dd>{{ isUnread(active) ? '未读' : '已读' }}</dd>
          </div>
          <div v-if="active.expireTime">
            <dt>有效期至</dt>
            <dd>{{ formatDate(active.expireTime) }}</dd>
          </div>
        </dl>
      </aside>
    </article>
  </div>
</template>

<script setup name="DcsAnnouncement" lang="ts">
import { listAnnouncements, markAnnouncementRead } from '@/api/dcs/announcement';
import type { AnnouncementRow } from '@/api/dcs/announcement/types';
import { useAnnouncementStore } from '@/store/modules/announcement';
import SupportPageHead from '@/views/dcs/support/components/SupportPageHead.vue';

const announcementStore = useAnnouncementStore();

const loading = ref(false);
const rows = ref<AnnouncementRow[]>([]);
const total = ref(0);
const active = ref<AnnouncementRow | null>(null);
const query = reactive({ pageNum: 1, pageSize: 8, readFlag: '' });

const unreadCount = computed(() => announcementStore.state.unreadCount);
const totalLabel = computed(() => {
  if (query.readFlag === '0') {
    return '条未读';
  }
  return query.readFlag === '1' ? '条已读' : '条公告';
});
const emptyText = computed(() => (query.readFlag === '0' ? '没有未读公告' : query.readFlag === '1' ? '还没有已读公告' : '暂无公告'));

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
const stripHtml = (html: string) =>
  html
    .replace(/<[^>]*>/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();

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
      // 本地只删一行会让分页少一条（第 9 条永远翻不到），交服务端重算本页
      await load();
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
  max-width: 1600px;
  margin: 0 auto;
}

/* ---------- 页头统计 ---------- */
.an-stats {
  display: flex;
  align-items: stretch;
}

.an-stat {
  padding: 0 20px;
  text-align: left;

  &:first-child {
    padding-left: 0;
  }

  & + & {
    border-left: 1px solid var(--dcs-border-color-lighter);
  }

  b {
    display: block;
    font-size: 26px;
    line-height: 1.1;
    font-weight: 700;
    letter-spacing: -0.02em;
    font-variant-numeric: tabular-nums;
    color: var(--dcs-text-primary);
  }

  span {
    display: block;
    margin-top: 4px;
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
  margin-bottom: 16px;
}

/* ---------- 卡片流 ---------- */
.an-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  min-height: 160px;
}

.an-empty {
  grid-column: 1 / -1;
  align-self: start;
  padding: 32px 0;
  border: 1px solid var(--dcs-border-color-lighter);
  border-radius: 16px;
}

.an-card {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 196px;
  padding: 18px 20px 16px;
  border: 1px solid var(--dcs-border-color-lighter);
  border-radius: 16px;
  background: var(--el-bg-color);
  cursor: pointer;
  outline: none;
  transition:
    border-color 0.18s ease,
    box-shadow 0.18s ease,
    transform 0.18s ease;

  &:hover,
  &:focus-visible {
    border-color: var(--el-color-primary-light-5);
    box-shadow: 0 12px 28px rgb(0 0 0 / 8%);
    transform: translateY(-2px);
  }

  &:focus-visible {
    box-shadow:
      0 0 0 3px var(--el-color-primary-light-7),
      0 12px 28px rgb(0 0 0 / 8%);
  }

  /* 首条占据两列，作为本页视觉入口 */
  &.is-lead {
    grid-column: span 2;
    min-height: 232px;
    padding: 24px 26px 20px;
    border-color: var(--el-color-primary-light-8);
    background: var(--el-color-primary-light-9);

    .an-card__title {
      font-size: 22px;
      line-height: 1.4;
      letter-spacing: -0.015em;
      -webkit-line-clamp: 3;
    }

    .an-card__excerpt {
      max-width: 68ch;
      font-size: 14.5px;
      -webkit-line-clamp: 3;
    }
  }

  &.is-only {
    grid-column: 1 / -1;
  }

  &__flags {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 8px;
    margin-bottom: 12px;
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
    margin-top: 16px;
    font-size: 12.5px;
    color: var(--dcs-text-placeholder);

    time {
      font-variant-numeric: tabular-nums;
    }
  }

  .an-more {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    color: var(--el-color-primary);
    font-weight: 500;
    white-space: nowrap;
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
  display: grid;
  grid-template-columns: minmax(0, 1fr) 280px;
  gap: 20px;
  align-items: start;
}

.an-article__main {
  min-width: 0;
  padding: 26px 32px 22px;
  border: 1px solid var(--dcs-border-color-lighter);
  border-radius: 16px;
  background: var(--el-bg-color);
}

.an-back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  margin: 0 0 18px -8px;
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
  padding-bottom: 18px;
  border-bottom: 1px solid var(--dcs-border-color-lighter);
}

.an-article__title {
  margin: 6px 0 0;
  max-width: 30em;
  font-size: 30px;
  line-height: 1.35;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--dcs-text-primary);
  overflow-wrap: anywhere;
}

.an-article__body {
  max-width: 78ch;
  padding: 22px 0 8px;
  font-size: 15px;
  line-height: 1.9;
  color: var(--dcs-text-regular);
  white-space: pre-wrap;
  overflow-wrap: anywhere;

  :deep(p) {
    margin: 0 0 12px;
  }

  :deep(p:last-child) {
    margin-bottom: 0;
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
  padding-top: 16px;
  border-top: 1px solid var(--dcs-border-color-lighter);
  font-size: 12.5px;
  color: var(--dcs-text-placeholder);
}

.an-article__side {
  padding: 18px;
  border: 1px solid var(--dcs-border-color-lighter);
  border-radius: 14px;
  background: var(--el-fill-color-lighter);
}

.an-side__label {
  margin: 0 0 14px;
  font-size: 12px;
  font-weight: 600;
  color: var(--dcs-text-secondary);
}

.an-side__list {
  display: flex;
  flex-direction: column;
  gap: 14px;
  margin: 0;

  div {
    display: flex;
    flex-direction: column;
    gap: 3px;
  }

  dt {
    font-size: 12px;
    color: var(--dcs-text-placeholder);
  }

  dd {
    margin: 0;
    font-size: 13.5px;
    line-height: 1.5;
    color: var(--dcs-text-regular);
    font-variant-numeric: tabular-nums;
  }
}

/* ---------- 响应式 ---------- */
@media (max-width: 1199px) {
  .an-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .an-card.is-lead {
    grid-column: span 1;
    min-height: 196px;
    padding: 18px 20px 16px;
    border-color: var(--dcs-border-color-lighter);
    background: var(--el-bg-color);

    .an-card__title {
      font-size: 16.5px;
      line-height: 1.5;
      letter-spacing: normal;
      -webkit-line-clamp: 2;
    }

    .an-card__excerpt {
      max-width: none;
      font-size: 13.5px;
      -webkit-line-clamp: 2;
    }
  }
}

@media (max-width: 992px) {
  .an-article {
    grid-template-columns: minmax(0, 1fr);
  }

  .an-article__side {
    position: static;
  }

  .an-card {
    min-height: 0;
  }
}

@media (max-width: 767px) {
  .an-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .an-card.is-only {
    grid-column: auto;
  }
}

@media (max-width: 768px) {
  .announcement-page {
    padding-left: 12px;
    padding-right: 12px;
  }
}

@media (max-width: 640px) {
  .an-stat {
    padding: 0 14px;

    b {
      font-size: 22px;
    }
  }

  .an-card {
    padding: 16px;
    border-radius: 14px;
  }

  .an-card__foot {
    margin-top: 14px;
  }

  .an-article__main {
    padding: 18px 16px;
    border-radius: 14px;
  }

  .an-article__title {
    font-size: 22px;
  }

  .an-article__body {
    font-size: 14.5px;
    line-height: 1.85;
  }
}
</style>
