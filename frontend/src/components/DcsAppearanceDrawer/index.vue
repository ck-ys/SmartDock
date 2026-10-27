<template>
  <el-drawer v-model="visible" title="外观" direction="rtl" size="min(360px, 92vw)" append-to-body class="dcs-appearance-drawer">
    <div class="dcs-appearance">
      <!-- 2026-10-27 重构拍板 Q8=甲：皮肤入口先隐藏，整套界面只留「一套设计语言 × 明暗两态」。
           T7 的令牌与切换函数全部保留（老用户 localStorage 里存着 t7 的仍按 t7 渲染，不闪不崩），
           只是不再对外提供第二个入口——验收范围由此从四组降到两组 -->
      <section v-if="false" class="dcs-appearance__section" aria-labelledby="dcs-appearance-skin">
        <h3 id="dcs-appearance-skin" class="dcs-appearance__title">皮肤</h3>
        <p class="dcs-appearance__hint">整套界面的配色骨架，与浅色/深色开关正交，切换后立即生效。</p>
        <div class="dcs-appearance__skin-options">
          <button
            v-for="item in skinOptions"
            :key="item.value"
            type="button"
            class="dcs-appearance__skin"
            :class="{ 'is-active': skin === item.value }"
            :aria-pressed="skin === item.value"
            @click="setSkin(item.value)"
          >
            <span class="dcs-appearance__skin-preview" :style="item.preview"> <i class="bar" /><i class="body" /> </span>
            <span class="dcs-appearance__skin-name">{{ item.label }}</span>
            <span class="dcs-appearance__skin-desc">{{ item.desc }}</span>
            <span v-if="skin === item.value" class="dcs-appearance__skin-on">当前</span>
          </button>
        </div>
      </section>

      <section class="dcs-appearance__section" aria-labelledby="dcs-appearance-theme">
        <h3 id="dcs-appearance-theme" class="dcs-appearance__title">明暗</h3>
        <p class="dcs-appearance__hint">浅色看表省力，深色夜间护眼；只换明暗，不动皮肤。</p>
        <div class="dcs-appearance__options">
          <button type="button" class="dcs-appearance__option" :class="{ 'is-active': !dark }" :aria-pressed="!dark" @click="setTheme(false)">
            <el-icon class="dcs-appearance__icon"><Sunny /></el-icon>
            <span class="dcs-appearance__label">浅色</span>
          </button>
          <button type="button" class="dcs-appearance__option" :class="{ 'is-active': dark }" :aria-pressed="dark" @click="setTheme(true)">
            <el-icon class="dcs-appearance__icon"><Moon /></el-icon>
            <span class="dcs-appearance__label">深色</span>
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

      <section class="dcs-appearance__section" aria-labelledby="dcs-appearance-lang">
        <h3 id="dcs-appearance-lang" class="dcs-appearance__title">语言</h3>
        <p class="dcs-appearance__hint">界面文案语言，切换后立即生效。</p>
        <div class="dcs-appearance__options">
          <button
            type="button"
            class="dcs-appearance__option"
            :class="{ 'is-active': language === 'zh_CN' }"
            :aria-pressed="language === 'zh_CN'"
            @click="setLanguage('zh_CN')"
          >
            <span class="dcs-appearance__label">中文</span>
          </button>
          <button
            type="button"
            class="dcs-appearance__option"
            :class="{ 'is-active': language === 'en_US' }"
            :aria-pressed="language === 'en_US'"
            @click="setLanguage('en_US')"
          >
            <span class="dcs-appearance__label">English</span>
          </button>
        </div>
      </section>

      <section class="dcs-appearance__section" aria-labelledby="dcs-appearance-size">
        <h3 id="dcs-appearance-size" class="dcs-appearance__title">字号</h3>
        <p class="dcs-appearance__hint">调整表格与表单的控件尺寸，不改页面布局。</p>
        <div class="dcs-appearance__options dcs-appearance__options--three">
          <button
            v-for="item in sizeOptions"
            :key="item.value"
            type="button"
            class="dcs-appearance__option"
            :class="{ 'is-active': size === item.value }"
            :aria-pressed="size === item.value"
            @click="setSize(item.value)"
          >
            <span class="dcs-appearance__label">{{ item.label }}</span>
          </button>
        </div>
      </section>

      <button type="button" class="dcs-appearance__reset" @click="resetAll">恢复默认外观</button>

      <p class="dcs-appearance__footnote">选择会保存在本机浏览器，下次打开自动生效。</p>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { useSettingsStore } from '@/store/modules/settings';
import { useAppStore } from '@/store/modules/app';
import type { AppSkin } from '@/utils/theme';

type DensityMode = 'comfortable' | 'compact';
type SizeMode = 'large' | 'default' | 'small';

const DENSITY_STORAGE_KEY = 'dcs:ui:density';
const DENSITY_CLASS = 'density-compact';

const settingsStore = useSettingsStore();
const appStore = useAppStore();
const { locale } = useI18n();
const visible = ref(false);
const dark = computed(() => settingsStore.dark);
const skin = computed(() => settingsStore.skin);
const language = computed(() => appStore.language);
const size = computed(() => appStore.size);

/* 皮肤卡缩略图：色值即 themes.mjs（T1/T7.light）的 shell/bg/acc，只做展示，不参与渲染 */
const skinOptions: { value: AppSkin; label: string; desc: string; preview: Record<string, string> }[] = [
  {
    value: 't1',
    label: 'T1 深蓝稳重',
    desc: '深色导航 + 亮色内容，政企数据平台骨架',
    preview: { '--pv-shell': '#1e2a38', '--pv-bg': '#eef1f6', '--pv-acc': '#2f6feb' }
  },
  {
    value: 't7',
    label: 'T7 通用蓝',
    desc: '白侧栏 + 亮蓝主色，通用中台范式',
    preview: { '--pv-shell': '#ffffff', '--pv-bg': '#f3f5f8', '--pv-acc': '#0052d9' }
  }
];

const sizeOptions: { label: string; value: SizeMode }[] = [
  { label: '较大', value: 'large' },
  { label: '默认', value: 'default' },
  { label: '稍小', value: 'small' }
];

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

const setSkin = (next: AppSkin) => {
  settingsStore.setSkin(next);
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

const setLanguage = (lang: 'zh_CN' | 'en_US') => {
  locale.value = lang;
  appStore.changeLanguage(lang);
};

const setSize = (next: SizeMode) => {
  appStore.setSize(next);
};

// 一键回到出厂外观：T1 + 浅色 + 舒适 + 中文 + 默认字号（各项都在本机 Storage，刷新后仍生效）
const resetAll = () => {
  setSkin('t1');
  setTheme(false);
  setDensity('comfortable');
  setLanguage('zh_CN');
  setSize('default');
  ElMessage.success('已恢复默认外观');
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

    &--three {
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }
  }

  /* 皮肤两卡：缩略图（侧栏条 + 内容面 + 强调点）+ 名称 + 一句人话；
     选中态 = 安静边框 + 文字徽章，无辉光无渐变（U-17/U-24 口径） */
  &__skin-options {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
  }

  &__skin {
    position: relative;
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding: 10px;
    border: 1px solid var(--app-border);
    border-radius: var(--app-control-radius);
    background: transparent;
    color: var(--app-muted);
    font-family: inherit;
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
      border-color: var(--app-accent);
      background: var(--app-accent-soft);
      color: var(--app-text);
    }
  }

  &__skin-preview {
    display: flex;
    width: 100%;
    height: 34px;
    overflow: hidden;
    border: 1px solid var(--app-rule);
    border-radius: 4px;

    .bar {
      flex: 0 0 26%;
      background: var(--pv-shell);
    }

    .body {
      position: relative;
      flex: 1;
      background: var(--pv-bg);

      /* 内容面右下角一个强调色小方点，代表该皮肤的 acc */
      &::after {
        content: '';
        position: absolute;
        right: 5px;
        bottom: 5px;
        width: 9px;
        height: 9px;
        border-radius: 2px;
        background: var(--pv-acc);
      }
    }
  }

  &__skin-name {
    color: var(--app-text);
    font-size: 13px;
    font-weight: 600;
    line-height: 1.4;
  }

  &__skin-desc {
    color: var(--app-faint);
    font-size: 11px;
    line-height: 1.5;
  }

  &__skin-on {
    position: absolute;
    top: 8px;
    right: 8px;
    padding: 1px 6px;
    border: 1px solid currentColor;
    border-radius: 999px;
    color: var(--app-accent);
    font-size: 10px;
    line-height: 1.5;
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

  &__reset {
    align-self: flex-start;
    height: 32px;
    padding: 0 12px;
    border: 1px solid var(--app-border);
    border-radius: var(--app-control-radius);
    background: transparent;
    color: var(--app-muted);
    font-family: inherit;
    font-size: 12px;
    cursor: pointer;
    transition:
      border-color 0.15s ease,
      color 0.15s ease;

    &:hover {
      border-color: var(--app-control);
      color: var(--app-text);
    }
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
