<template>
  <el-dialog
    v-model="open"
    class="feedback-dialog"
    width="720px"
    top="7vh"
    append-to-body
    :close-on-click-modal="false"
  >
    <template #header>
      <div class="fd-head">
        <span class="fd-head__dot" aria-hidden="true"></span>
        <div>
          <p class="fd-head__title">反馈问题</p>
          <p class="fd-head__sub">遇到 Bug、体验不顺手、想要新功能，都可以直接说。</p>
        </div>
      </div>
    </template>

    <el-tabs v-model="tab" class="fd-tabs">
      <el-tab-pane name="submit">
        <template #label>
          <span class="fd-tab">提交反馈</span>
        </template>

        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="fd-form">
          <el-form-item prop="feedbackType">
            <el-radio-group v-model="form.feedbackType" class="fd-types">
              <el-radio-button value="BUG">缺陷</el-radio-button>
              <el-radio-button value="EXPERIENCE">体验</el-radio-button>
              <el-radio-button value="SUGGEST">建议</el-radio-button>
              <el-radio-button value="OTHER">其它</el-radio-button>
            </el-radio-group>
          </el-form-item>

          <el-form-item label="一句话标题" prop="title">
            <el-input v-model="form.title" maxlength="200" show-word-limit placeholder="例如：文档导出后表格列错位" />
          </el-form-item>

          <el-form-item label="详细描述" prop="content">
            <el-input
              v-model="form.content"
              type="textarea"
              :rows="4"
              maxlength="2000"
              show-word-limit
              placeholder="做了什么、期望看到什么、实际发生了什么。有报错信息的话贴在这里会更快定位。"
            />
          </el-form-item>

          <el-form-item label="截图（选填，最多 3 张）">
            <image-upload v-model="form.ossIds" :limit="3" :data="{ bizSource: 'FEEDBACK' }" />
          </el-form-item>

          <el-form-item label="联系方式（选填）">
            <el-input v-model="form.contact" maxlength="100" placeholder="邮箱 / 手机号，方便处理时回访" />
          </el-form-item>

          <p class="fd-hint">
            <el-icon><InfoFilled /></el-icon>
            提交时会自动带上当前页面、浏览器与屏幕信息，帮助定位问题；不会记录密码等隐私内容。
          </p>
        </el-form>
      </el-tab-pane>

      <el-tab-pane name="mine">
        <template #label>
          <span class="fd-tab">
            我的反馈
            <span v-if="pendingCount > 0" class="fd-tab__badge">{{ pendingCount > 99 ? '99+' : pendingCount }}</span>
          </span>
        </template>

        <div class="fd-summary">
          <button
            v-for="item in summaryCards"
            :key="item.key"
            type="button"
            class="fd-summary__item"
            :class="{ 'is-active': mineStatus === item.key }"
            @click="filterMine(item.key)"
          >
            <b>{{ summaryCount(item.key) }}</b>
            <span>{{ item.label }}</span>
          </button>
        </div>

        <div v-loading="mineLoading" class="fd-mine">
          <article v-for="row in mine" :key="row.feedbackId" class="fd-item">
            <div class="fd-item__head">
              <span class="fd-type" :class="`is-${(row.feedbackType || 'other').toLowerCase()}`">{{ typeLabel(row.feedbackType) }}</span>
              <span class="fd-item__title">{{ row.title }}</span>
              <span class="fd-status" :class="`is-${(row.status || 'pending').toLowerCase()}`">{{ statusLabel(row.status) }}</span>
            </div>
            <p class="fd-item__content">{{ row.content }}</p>
            <div class="fd-item__meta">
              <span>{{ row.createTime }}</span>
              <span v-if="row.ossIdList?.length">· {{ row.ossIdList.length }} 张截图</span>
            </div>
            <div v-if="row.handleNote" class="fd-reply">
              <p class="fd-reply__who">
                官方回复
                <span v-if="row.handlerName">· {{ row.handlerName }}</span>
                <span v-if="row.handleTime" class="fd-reply__time">{{ row.handleTime }}</span>
              </p>
              <p class="fd-reply__text">{{ row.handleNote }}</p>
            </div>
          </article>

          <el-empty v-if="!mineLoading && mine.length === 0" description="还没有提交过反馈" :image-size="64" />
        </div>

        <pagination
          v-show="mineTotal > mineQuery.pageSize"
          v-model:page="mineQuery.pageNum"
          v-model:limit="mineQuery.pageSize"
          layout="prev, pager, next"
          :total="mineTotal"
          :auto-scroll="false"
          @pagination="loadMine"
        />
      </el-tab-pane>
    </el-tabs>

    <!-- 主操作固定在弹窗底部，内容再长也不用滚到底才能提交 -->
    <template #footer>
      <div class="fd-actions">
        <template v-if="tab === 'submit'">
          <el-button @click="open = false">取消</el-button>
          <el-button type="primary" :loading="submitting" @click="submit">提交反馈</el-button>
        </template>
        <el-button v-else @click="open = false">关闭</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
import { InfoFilled } from '@element-plus/icons-vue';
import ImageUpload from '@/components/ImageUpload/index.vue';
import { myFeedbackSummary, myFeedbacks, submitFeedback } from '@/api/dcs/feedback';
import type { FeedbackRow, FeedbackStatus, FeedbackSubmit, FeedbackType } from '@/api/dcs/feedback/types';

const props = defineProps<{ visible: boolean; initialTab?: 'submit' | 'mine' }>();
const emit = defineEmits<{ (e: 'update:visible', value: boolean): void }>();
const route = useRoute();

const open = computed({
  get: () => props.visible,
  set: (value: boolean) => emit('update:visible', value)
});

const tab = ref<'submit' | 'mine'>('submit');
const formRef = ref<FormInstance>();
const submitting = ref(false);
const form = reactive<FeedbackSubmit>({
  feedbackType: 'BUG',
  title: '',
  content: '',
  ossIds: '',
  contact: '',
  contextJson: ''
});

const rules: FormRules = {
  feedbackType: [{ required: true, message: '请选择反馈类型', trigger: 'change' }],
  title: [
    { required: true, message: '请填写标题', trigger: 'blur' },
    { min: 4, max: 200, message: '标题 4-200 个字', trigger: 'blur' }
  ],
  content: [
    { required: true, message: '请填写详细描述', trigger: 'blur' },
    { min: 10, max: 2000, message: '描述 10-2000 个字', trigger: 'blur' }
  ]
};

const summary = ref<Record<string, number>>({});
const summaryCards: { key: '' | FeedbackStatus; label: string }[] = [
  { key: '', label: '全部' },
  { key: 'PENDING', label: '待处理' },
  { key: 'PROCESSING', label: '处理中' },
  { key: 'RESOLVED', label: '已解决' },
  { key: 'CLOSED', label: '已关闭' }
];
const pendingCount = computed(() => Number(summary.value.PENDING ?? 0));
/** 后端四态计数不含「全部」键，这里求和（管理端同口径）。 */
const summaryTotal = computed(() =>
  Object.values(summary.value).reduce((sum, value) => sum + Number(value || 0), 0)
);
const summaryCount = (key: '' | FeedbackStatus) =>
  key === '' ? summaryTotal.value : Number(summary.value[key] ?? 0);

const mineStatus = ref<'' | FeedbackStatus>('');
const mine = ref<FeedbackRow[]>([]);
const mineTotal = ref(0);
const mineLoading = ref(false);
const mineQuery = reactive({ pageNum: 1, pageSize: 5 });

const TYPE_LABELS: Record<FeedbackType, string> = { BUG: '缺陷', EXPERIENCE: '体验', SUGGEST: '建议', OTHER: '其它' };
const STATUS_LABELS: Record<FeedbackStatus, string> = { PENDING: '待处理', PROCESSING: '处理中', RESOLVED: '已解决', CLOSED: '已关闭' };
const typeLabel = (type?: string) => TYPE_LABELS[(type as FeedbackType) || 'OTHER'] || '其它';
const statusLabel = (status?: string) => STATUS_LABELS[(status as FeedbackStatus) || 'PENDING'] || '待处理';

/** 自动上下文：只带定位所需信息，超长截断，避免撑爆 2000 字上限。 */
const buildContext = () => {
  // 先按字段截断再序列化：直接切 JSON 字符串会截出非法 JSON，管理端整段解析失败
  const clip = (value: string, max: number) => (value.length > max ? `${value.slice(0, max)}…` : value);
  const context = {
    route: clip(route.fullPath, 300),
    title: clip(document.title, 120),
    ua: clip(navigator.userAgent, 300),
    screen: `${window.screen.width}x${window.screen.height}`,
    lang: navigator.language,
    at: new Date().toISOString()
  };
  return JSON.stringify(context);
};

const resetForm = () => {
  form.feedbackType = 'BUG';
  form.title = '';
  form.content = '';
  form.ossIds = '';
  form.contact = '';
  form.contextJson = '';
  formRef.value?.clearValidate();
};

let loadMineSeq = 0;

const loadMine = async () => {
  const current = ++loadMineSeq;
  mineLoading.value = true;
  try {
    const res: any = await myFeedbacks({ status: mineStatus.value || undefined, pageNum: mineQuery.pageNum, pageSize: mineQuery.pageSize });
    if (current !== loadMineSeq) {
      return;
    }
    mine.value = res.rows || [];
    mineTotal.value = Number(res.total || 0);
  } finally {
    if (current === loadMineSeq) {
      mineLoading.value = false;
    }
  }
};

const loadSummary = async () => {
  try {
    const res: any = await myFeedbackSummary();
    summary.value = res.data || {};
  } catch {
    summary.value = {};
  }
};

const filterMine = (status: '' | FeedbackStatus) => {
  mineStatus.value = status;
  mineQuery.pageNum = 1;
  loadMine();
};

// 打开时按入口切页签：用 props.visible 监听比 el-dialog 的 open 事件稳
// （深链 /index?feedback=mine 曾在 open 事件里读到旧的 initialTab，落在「提交反馈」页）
watch(
  () => [props.visible, props.initialTab] as const,
  ([visible, initialTab]) => {
    if (!visible) {
      return;
    }
    tab.value = initialTab === 'mine' ? 'mine' : 'submit';
    void loadSummary();
    void loadMine();
  },
  { immediate: true }
);

const submit = async () => {
  await formRef.value?.validate();
  submitting.value = true;
  try {
    await submitFeedback({ ...form, contextJson: buildContext() });
    ElMessage.success('反馈已提交，处理进展会出现在「我的反馈」里');
    resetForm();
    tab.value = 'mine';
    mineStatus.value = '';
    mineQuery.pageNum = 1;
    await Promise.all([loadMine(), loadSummary()]);
  } finally {
    submitting.value = false;
  }
};
</script>

<style lang="scss" scoped>
.fd-head {
  display: flex;
  gap: 10px;
  align-items: flex-start;

  &__dot {
    flex: none;
    width: 8px;
    height: 8px;
    margin-top: 7px;
    border-radius: 50%;
    background: var(--el-color-primary);
    box-shadow: 0 0 0 4px var(--el-color-primary-light-9);
  }

  &__title {
    margin: 0;
    font-size: 17px;
    font-weight: 600;
    color: var(--dcs-text-primary);
  }

  &__sub {
    margin: 3px 0 0;
    font-size: 12.5px;
    line-height: 1.6;
    color: var(--dcs-text-placeholder);
  }
}

.fd-tabs {
  margin-top: -6px;
}

.fd-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;

  &__badge {
    min-width: 18px;
    padding: 0 5px;
    border-radius: 999px;
    background: var(--el-color-danger);
    color: var(--el-color-white);
    font-size: 11px;
    line-height: 18px;
    text-align: center;
  }
}

.fd-form {
  :deep(.el-form-item) {
    margin-bottom: 16px;
  }

  :deep(.el-form-item__label) {
    padding-bottom: 4px;
    font-size: 13px;
    color: var(--dcs-text-secondary);
  }
}

.fd-types {
  :deep(.el-radio-button__inner) {
    padding: 8px 18px;
  }
}

.fd-hint {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: 2px 0 0;
  padding: 9px 11px;
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
  font-size: 12px;
  line-height: 1.65;
  color: var(--dcs-text-placeholder);
}

.fd-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.fd-summary {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 14px;

  &__item {
    padding: 9px 6px;
    border: 1px solid var(--dcs-border-color-lighter);
    border-radius: 9px;
    background: var(--el-bg-color);
    cursor: pointer;
    transition:
      border-color 0.15s ease,
      background 0.15s ease;

    b {
      display: block;
      font-size: 18px;
      line-height: 1.2;
      font-variant-numeric: tabular-nums;
      color: var(--dcs-text-primary);
    }

    span {
      display: block;
      margin-top: 1px;
      font-size: 11.5px;
      color: var(--dcs-text-placeholder);
    }

    &:hover {
      border-color: var(--el-color-primary-light-5);
    }

    &.is-active {
      border-color: var(--el-color-primary-light-3);
      background: var(--el-color-primary-light-9);

      b {
        color: var(--el-color-primary);
      }
    }
  }
}

.fd-mine {
  min-height: 180px;
  max-height: 46vh;
  overflow: auto;
  padding-right: 2px;
}

.fd-item {
  padding: 12px 14px;
  border: 1px solid var(--dcs-border-color-lighter);
  border-radius: 10px;

  & + & {
    margin-top: 10px;
  }

  &__head {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 7px;
  }

  &__title {
    flex: 1;
    min-width: 0;
    font-size: 14px;
    font-weight: 600;
    color: var(--dcs-text-primary);
    overflow-wrap: anywhere;
  }

  &__content {
    margin: 7px 0 0;
    font-size: 13px;
    line-height: 1.7;
    color: var(--dcs-text-secondary);
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    display: -webkit-box;
    -webkit-line-clamp: 3;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }

  &__meta {
    display: flex;
    gap: 4px;
    margin-top: 7px;
    font-size: 11.5px;
    color: var(--dcs-text-placeholder);
    font-variant-numeric: tabular-nums;
  }
}

.fd-type,
.fd-status {
  flex: none;
  padding: 1px 7px;
  border-radius: 999px;
  font-size: 11px;
  line-height: 18px;
}

.fd-type {
  background: var(--el-fill-color-light);
  color: var(--dcs-text-secondary);

  &.is-bug {
    background: var(--el-color-danger-light-9);
    color: var(--el-color-danger);
  }

  &.is-suggest {
    background: var(--el-color-primary-light-9);
    color: var(--el-color-primary);
  }

  &.is-experience {
    background: var(--el-color-warning-light-9);
    color: var(--el-color-warning);
  }
}

.fd-status {
  &.is-pending {
    background: var(--el-color-warning-light-9);
    color: var(--el-color-warning);
  }

  &.is-processing {
    background: var(--el-color-primary-light-9);
    color: var(--el-color-primary);
  }

  &.is-resolved {
    background: var(--el-color-success-light-9);
    color: var(--el-color-success);
  }

  &.is-closed {
    background: var(--el-fill-color-light);
    color: var(--dcs-text-placeholder);
  }
}

.fd-reply {
  margin-top: 9px;
  padding: 9px 11px;
  border-radius: 8px;
  background: var(--el-color-primary-light-9);
  border-left: 3px solid var(--el-color-primary-light-5);

  &__who {
    margin: 0;
    font-size: 11.5px;
    color: var(--el-color-primary);
  }

  &__time {
    margin-left: 8px;
    color: var(--dcs-text-placeholder);
    font-variant-numeric: tabular-nums;
  }

  &__text {
    margin: 4px 0 0;
    font-size: 13px;
    line-height: 1.7;
    color: var(--dcs-text-regular);
    white-space: pre-wrap;
    overflow-wrap: anywhere;
  }
}

@media (max-width: 768px) {
  .fd-summary {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .fd-mine {
    max-height: 40vh;
  }
}
</style>
