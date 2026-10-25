<template>
  <el-drawer
    v-model="visible"
    title="外观"
    direction="rtl"
    size="min(360px, 92vw)"
    append-to-body
    class="dcs-appearance-drawer"
  >
    <div class="dcs-appearance">
      <section class="dcs-appearance__section" aria-labelledby="dcs-appearance-theme">
        <h3 id="dcs-appearance-theme" class="dcs-appearance__title">主题</h3>
        <p class="dcs-appearance__hint">与顶栏的主题按钮共用同一套切换逻辑，切换后立即生效。</p>
        <div class="dcs-appearance__options">
          <button
            type="button"
            class="dcs-appearance__option"
            :class="{ 'is-active': !dark }"
            :aria-pressed="!dark"
            @click="setTheme(false)"
          >
            <el-icon class="dcs-appearance__icon"><Sunny /></el-icon>
            <span class="dcs-appearance__label">浅色 · 框架纸</span>
          </button>
          <button
            type="button"
            class="dcs-appearance__option"
            :class="{ 'is-active': dark }"
            :aria-pressed="dark"
            @click="setTheme(true)"
          >
            <el-icon class="dcs-appearance__icon"><Moon /></el-icon>
            <span class="dcs-appearance__label">深色 · 纯黑夜</span>
          </button>
        </div>
      </section>

      <section class="dcs-appearance__section" aria-labelledby="dcs-appearance-density">
        <h3 id="dcs-appearance-density" class="dcs-appearance__title">密度</h3>
        <p class="dcs-appearance__hint">只调整表格行高、工具条与指标带的留白，不改页面布局结构。</p>
        <div class="dcs-appearance__options">
          <button
            type="button"
            class="dcs-appearance__option dcs-appearance__option--density"
            :class="{ 'is-active': density === 'comfortable' }"
            :aria-pressed="density === 'comfortable'"
            @click="setDensity('comfortable')"
          >
            <span class="dcs-appearance__label">舒适</span>
            <span class="dcs-appearance__desc">默认留白，适合日常浏览</span>
          </button>
          <button
            type="button"
            class="dcs-appearance__option dcs-appearance__option--density"
            :class="{ 'is-active': density === 'compact' }"
            :aria-pressed="density === 'compact'"
            @click="setDensity('compact')"
          >
            <span class="dcs-appearance__label">紧凑</span>
            <span class="dcs-appearance__desc">一屏多看几行，适合密集盘点</span>
          </button>
        </div>
      </section>

      <p class="dcs-appearance__footnote">选择会保存在本机浏览器，下次打开自动生效。</p>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { useSettingsStore } from '@/store/modules/settings';

type DensityMode = 'comfortable' | 'compact';

const DENSITY_STORAGE_KEY = 'dcs:ui:density';
const DENSITY_CLASS = 'density-compact';

const settingsStore = useSettingsStore();
const visible = ref(false);
const dark = computed(() => settingsStore.dark);

const readDensity = (): DensityMode => {
  try {
    return localStorage.getItem(DENSITY_STORAGE_KEY) === 'compact' ? 'compact' : 'comfortable';
  } catch {
    return 'comfortable';
  }
};

const density = ref<DensityMode>(readDensity());

const applyDensity = (mode: DensityMode) => {
  document.documentElement.classList.toggle(DENSITY_CLASS, mode === 'compact');
};

// setup 阶段就应用（早于首次绘制），避免刷新后密度闪一下再切
applyDensity(density.value);

const setTheme = (nextDark: boolean) => {
  settingsStore.setDark(nextDark);
};

const setDensity = (mode: DensityMode) => {
  density.value = mode;
  applyDensity(mode);
  try {
    localStorage.setItem(DENSITY_STORAGE_KEY, mode);
  } catch {
    // 隐私模式不可写，不阻断交互
  }
};

const open = () => {
  visible.value = true;
};

defineExpose({ open });
</script>

<style lang="scss">
/* append-to-body 抽屉样式必须顶层化（契约 §3.4） */
.dcs-appearance-drawer.el-drawer {
  border-left: 1px solid var(--app-border);
  background: var(--app-surface);
  box-shadow: none;

  .el-drawer__header {
    margin-bottom: 0;
    padding: 16px 20px;
    border-bottom: 1px solid var(--app-rule);
    color: var(--app-text);
    font-size: 15px;
    font-weight: 600;
  }

  .el-drawer__body {
    padding: 16px 20px;
  }
}

.dcs-appearance {
  display: flex;
  flex-direction: column;
  gap: 20px;

  &__section {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  &__title {
    margin: 0;
    color: var(--app-text);
    font-size: 13px;
    font-weight: 600;
    line-height: 1.4;
  }

  &__hint {
    margin: 0;
    color: var(--app-muted);
    font-size: 12px;
    line-height: 1.6;
  }

  &__options {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
  }

  &__option {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
    padding: 10px 12px;
    border: 1px solid var(--app-border);
    border-radius: var(--app-control-radius);
    background: transparent;
    color: var(--app-muted);
    font-family: inherit;
    font-size: 13px;
    text-align: left;
    cursor: pointer;
    transition:
      border-color 0.15s ease,
      background 0.15s ease,
      color 0.15s ease;

    &:hover {
      border-color: var(--app-control);
      color: var(--app-text);
    }

    &.is-active {
      border-color: var(--app-control);
      background: var(--app-active);
      color: var(--app-text);
    }

    &--density {
      flex-direction: column;
      align-items: flex-start;
      gap: 2px;
    }
  }

  &__icon {
    flex: none;
    font-size: 15px;
  }

  &__label {
    overflow: hidden;
    font-weight: 500;
    line-height: 1.4;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__desc {
    color: var(--app-faint);
    font-size: 11px;
    font-weight: 400;
    line-height: 1.4;
  }

  &__footnote {
    margin: 0;
    padding-top: 12px;
    border-top: 1px solid var(--app-rule);
    color: var(--app-faint);
    font-size: 11px;
    line-height: 1.5;
  }
}
</style>
