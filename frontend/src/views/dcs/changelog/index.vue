<template>
  <div class="app-container changelog-page">
    <header class="cl-head">
      <div class="cl-head-main">
        <h1 class="cl-h1">更新日志</h1>
        <p class="cl-sub">每次改进都用大白话记在这里，方便你快速知道系统变了什么。</p>
      </div>
      <span class="cl-latest" aria-label="最新版本">最新 · {{ latest.version }}</span>
    </header>

    <section class="cl-list" aria-label="版本记录">
      <article v-for="(entry, i) in entries" :key="entry.version" class="cl-item">
        <span class="cl-dot" :class="{ on: i === 0 }" aria-hidden="true" />
        <div class="cl-card">
          <div class="cl-meta">
            <span class="cl-ver">{{ entry.version }}</span>
            <time class="cl-date" :datetime="entry.date">{{ formatDate(entry.date) }}</time>
            <span v-if="i === 0" class="cl-badge">本次更新</span>
          </div>
          <h2 class="cl-title">{{ entry.title }}</h2>
          <ul class="cl-points">
            <li v-for="(item, idx) in entry.items" :key="idx">{{ item }}</li>
          </ul>
        </div>
      </article>
    </section>

    <p class="cl-foot">更早的改动不再逐条列出。想了解某次变化怎么用，可以直接问右下角的小助手。</p>
  </div>
</template>

<script setup name="DcsChangelog" lang="ts">
import { CHANGELOG_ENTRIES, type ChangelogEntry } from './changelog';

const entries: ChangelogEntry[] = CHANGELOG_ENTRIES;
const latest = entries[0];

/** 2026-10-19 → 2026年10月19日（业务口径，不用斜杠/ISO） */
const formatDate = (date: string): string => {
  const [y, m, d] = date.split('-');
  return `${y}年${Number(m)}月${Number(d)}日`;
};
</script>

<style lang="scss" scoped>
.changelog-page {
  max-width: 880px;
  margin: 0 auto;
}

.cl-head {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 20px;
  padding: 18px 20px;
  background: var(--el-bg-color);
  border: 1px solid var(--dcs-border-color-lighter);
  border-radius: 12px;

  .cl-head-main {
    min-width: 0;
  }

  .cl-h1 {
    margin: 0;
    font-size: 22px;
    line-height: 1.35;
    font-weight: 600;
    color: var(--dcs-text-primary);
  }

  .cl-sub {
    margin: 6px 0 0;
    font-size: 14px;
    line-height: 1.7;
    color: var(--dcs-text-secondary);
  }

  .cl-latest {
    flex: none;
    margin-left: auto;
    padding: 3px 10px;
    border-radius: 999px;
    background: var(--el-color-primary-light-9);
    color: var(--el-color-primary);
    border: 1px solid var(--el-color-primary-light-7);
    font-size: 12px;
    line-height: 20px;
    white-space: nowrap;
  }
}

.cl-list {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.cl-item {
  position: relative;
  display: flex;
  gap: 14px;
  padding-bottom: 16px;

  /* 时间线竖线：最后一个条目不画 */
  &:not(:last-child)::before {
    content: '';
    position: absolute;
    left: 5px;
    top: 18px;
    bottom: 0;
    width: 1px;
    background: var(--dcs-border-color-lighter);
  }

  .cl-dot {
    flex: none;
    width: 11px;
    height: 11px;
    margin-top: 20px;
    border-radius: 50%;
    background: var(--el-bg-color);
    border: 2px solid var(--dcs-border-color);
    box-sizing: border-box;

    &.on {
      background: var(--el-color-primary);
      border-color: var(--el-color-primary);
    }
  }

  .cl-card {
    flex: 1;
    min-width: 0;
    padding: 16px 18px;
    background: var(--el-bg-color);
    border: 1px solid var(--dcs-border-color-lighter);
    border-radius: 12px;
  }

  .cl-meta {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 8px;
    margin-bottom: 8px;
  }

  .cl-ver {
    font-size: 13px;
    font-weight: 600;
    color: var(--el-color-primary);
    font-variant-numeric: tabular-nums;
  }

  .cl-date {
    font-size: 12.5px;
    color: var(--dcs-text-placeholder);
    font-variant-numeric: tabular-nums;
  }

  .cl-badge {
    padding: 1px 8px;
    border-radius: 999px;
    background: var(--el-color-success-light-9);
    color: var(--el-color-success);
    font-size: 11.5px;
    line-height: 18px;
  }

  .cl-title {
    margin: 0 0 10px;
    font-size: 16px;
    line-height: 1.55;
    font-weight: 600;
    color: var(--dcs-text-primary);
  }

  .cl-points {
    margin: 0;
    padding: 0;
    list-style: none;

    li {
      position: relative;
      padding-left: 16px;
      font-size: 14px;
      line-height: 1.75;
      color: var(--dcs-text-regular);
      overflow-wrap: anywhere;

      & + li {
        margin-top: 6px;
      }

      &::before {
        content: '';
        position: absolute;
        left: 2px;
        top: 0.72em;
        width: 5px;
        height: 5px;
        border-radius: 50%;
        background: var(--el-color-primary-light-5);
      }
    }
  }
}

.cl-foot {
  margin: 8px 0 0;
  padding-left: 25px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--dcs-text-placeholder);
}

@media (max-width: 768px) {
  .changelog-page {
    padding-left: 12px;
    padding-right: 12px;
  }

  .cl-head {
    flex-direction: column;
    gap: 10px;
    padding: 14px 14px;

    .cl-h1 {
      font-size: 20px;
    }

    .cl-latest {
      margin-left: 0;
    }
  }

  .cl-item {
    gap: 10px;

    .cl-dot {
      margin-top: 16px;
    }

    .cl-card {
      padding: 13px 14px;
      border-radius: 10px;
    }

    .cl-title {
      font-size: 15px;
    }

    .cl-points li {
      font-size: 14px;
      line-height: 1.7;
    }
  }

  .cl-foot {
    padding-left: 0;
  }
}
</style>
