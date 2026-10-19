<template>
  <div class="dcs-hero">
    <div class="hero-card">
      <!-- 今日概况行（原型 snaprow：仅全屏形态注入；待办/逾期/待修改/上次） -->
      <div v-if="snap && snap.length" class="snaprow">
        <span class="sn">今日概况</span>
        <span v-for="it in snap" :key="it.label" :class="{ wn2: it.warn }"
          ><IconSpan :name="it.icon" /> {{ it.label }} <b>{{ it.value }}</b></span
        >
      </div>
      <div class="hc-head">
        <span class="orb2"><IconSpan name="bot" :size="20" /></span>
        <b>{{ greet.title }}</b>
      </div>
      <!-- 页面感知上下文行（原型 ptxt：回访=「上次你问到…」召回；首访=「看到你在「X」页」） -->
      <p class="sub">
        <template v-if="lastAsk"
          >上次你问到「{{ lastAsk.slice(0, 16) }}{{ lastAsk.length > 16 ? '…' : '' }}」——继续追问，或从下面挑一件 · {{ greet.sub }}</template
        >
        <template v-else>看到你在「{{ pageName }}」页，{{ greet.sub }}</template>
      </p>
      <!-- 层2 新手行：3 问毕业（v3.17）；批17 Q7 加「界面导览」聚光灯带看 -->
      <div v-if="newbieUse < 3" class="newbie">
        <span>第一次用？</span>
        <button @click="emit('tour')"><IconSpan name="map" /> 界面导览</button>
        <button @click="emit('pick', SUGGESTIONS[0][3])"><IconSpan name="clapperboard" /> 跟我走一遍</button>
        <!-- 原型 stepsTry：成员回填「我的待办」，发布人/管理员回填「未交名单」 -->
        <button @click="emit('fill', roleKey === 'member' ? '我的待办' : '未交名单')"><IconSpan name="pencil" /> 帮我填一句</button>
        <button class="link" @click="emit('openHelp')"><IconSpan name="help" /> 完整说明</button>
      </div>
      <div v-else class="newbie gone">
        <button class="link" @click="emit('openHelp')">使用说明</button>
      </div>
      <!-- 五需求批 R4b 演示模式（Q7-C）：一键剧本连问连答——给负责人演示/内训用，
           剧本点击后由 MsgFlow 续跑器逐步自动发送（等确认卡时暂停，完成后续跑） -->
      <div class="demo-row">
        <button class="link" :aria-expanded="demoOpen" @click="demoOpen = !demoOpen"><IconSpan name="clapperboard" /> 演示模式</button>
      </div>
      <div v-if="demoOpen" class="demo" role="dialog" aria-label="演示模式剧本">
        <div class="demo-hd">
          <b>一键演示 · 十二幕小剧场</b>
          <!-- 业务批8 Q5：封面结果导向——先说「你会看到什么」，再列幕名预告 -->
          <span>你会看到：助手自动连问连答，从建任务、看数据到催办收尾跑完一整段工作，中途可停</span>
          <!-- step5 P2-D9 回修：演示是真模型真操作（催办等写操作真实发通知，关键步骤有人工确认闸）——
               风险须前置披露，不能等成员收到真实催办才发现 -->
          <span class="demo-note">演示会在本系统留下真实记录（如催办通知真实发出）；关键写操作会先弹出确认，由你把关</span>
          <!-- 业务批(1015) Q11：演示=真模型真计费，扣哪本账必须前置说清（用户实测"积分流水没有"根因即当时走钱包） -->
          <span class="demo-note demo-bill"
            >演示按当前消费方式真实计费：<b>{{ demoBillText }}</b
            >；流水分别记在「积分套餐 / 钱包流水」页</span
          >
          <!-- 打分席位回修（2026-10-15）：原此处再密排一遍 1.~9. 幕名小字（demo-reel），与下方
               九张幕卡的幕号+幕名完全重复且难读（glm53flash P2 + m3 P1 双报）——幕号幕名以卡片为准 -->
        </div>
        <button
          v-for="(d, i) in demoList"
          :key="d.title"
          class="s0 demo-card"
          :title="d.steps.join(' → ')"
          @click="emit('demo-start', d.steps, d.title)"
        >
          <span class="demo-no">{{ i + 1 }}</span>
          <span class="s-ic"><IconSpan :name="d.icon" /></span>
          <span class="s-txt"
            ><b>{{ d.title }}</b
            ><span class="s-sub">{{ d.desc }}</span></span
          >
          <span class="demo-play">{{ d.steps.length }} 步</span>
        </button>
      </div>
      <!-- 层3 建议卡四元组（短标题+口语补语；点击发完整指令；悬停 title 预览）+换一批 -->
      <div class="sughd">
        <span>在这个页面我能帮你</span>
        <button class="rfbtn" @click="refresh">换一批</button>
      </div>
      <div class="sug">
        <button v-for="s in shown" :key="s[1]" class="s0" :title="s[3]" @click="emit('pick', s[3])">
          <span class="s-ic"><IconSpan :name="s[0]" /></span>
          <span class="s-txt">
            <b>{{ s[1] }}</b>
            <span class="s-sub">{{ s[2] }}</span>
          </span>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 欢迎卡三层（v3.17 区域4 → PROTO PARITY 批A 页面感知）：
 * 层1 问候（首访时段问候/回访三变体按日轮换）+「看到你在「X」页」上下文行；
 * 层2 新手行（3 问毕业弱化）；层3 建议卡四元组+换一批轮换去重——池按当前页面出，成员剔除催办词。
 */
import { useRoute } from 'vue-router';
import { useUserStore } from '@/store/modules/user';
import { roleOf, pageNameOf, suggestionsFor, type SugItem } from './assistantCtx';
import { useHelpModal } from './helpModal';
import IconSpan from './IconSpan.vue';
import { getAssistantStatus } from '@/api/dcs/assistant';
import { unwrap } from './util';

/** 今日概况条目（全屏形态；warn=true 警示色） */
export interface SnapItem {
  icon: string;
  label: string;
  value: string | number;
  warn?: boolean;
}

const props = defineProps<{ newbieUse: number; snap?: SnapItem[] }>();
const emit = defineEmits<{
  pick: [prompt: string];
  /** BN-6 原型 stepsTry：帮我填一句=回填输入框+toast（不直接发） */
  fill: [text: string];
  /** 五需求批 R4b 演示模式：剧本步骤序列+幕名（顶条预告），由 MsgFlow 续跑器逐步发 */
  'demo-start': [steps: string[], title?: string];
  /** 批17 Q7：界面导览（driver.js 聚光灯带看，宿主开抽屉后跑） */
  tour: [];
  openHelp: [];
}>();

const help = useHelpModal();
const route = useRoute();
const pageName = computed(() => pageNameOf(route));
const roleKey = computed(() => roleOf(useUserStore().roles, useUserStore().permissions));

/** DP-6 原型 lastAsk 召回行：最近一问（send 时落 localStorage，与 thread persistKey 同键源） */
const lastAsk = ref('');
try {
  lastAsk.value = localStorage.getItem(`dcs:assistant:lastAsk:${String(useUserStore().userId ?? '')}`) ?? '';
} catch {
  /* 存储不可用忽略 */
}

/** 页面感知建议池（assistantCtx：页面命中出页面池，否则通用池；成员已剔除催办） */
const SUGGESTIONS = computed<SugItem[]>(() => suggestionsFor(pageName.value, roleKey.value));

/** 演示模式面板开合（五需求批 R4b，Q7-C）；五审回修（step5 P2-9）：补外点收起 */
const demoOpen = ref(false);
function onDemoDocDown(ev: MouseEvent) {
  if (!(ev.target as HTMLElement)?.closest('.demo-row, .demo')) {
    demoOpen.value = false;
  }
}
watch(demoOpen, (open) => {
  document.removeEventListener('mousedown', onDemoDocDown);
  if (open) {
    setTimeout(() => document.addEventListener('mousedown', onDemoDocDown), 0);
  }
});
onBeforeUnmount(() => document.removeEventListener('mousedown', onDemoDocDown));

/** 业务批(1015) Q11：演示计费去向披露——面板首次打开现查消费方式（status.billingMode 为
 *  权威锚，与 Composer 状态卡同源）；只拉一次缓存；
 *  自查回修(dspro P2)：①失败落到「unknown」中性文案（此前保持 null 永久显示"获取中…"）；
 *  ②加在途标志防开-关-开快速切换时并发重拉 */
const demoBilling = ref<'credits' | 'wallet' | 'unknown' | null>(null);
let demoBillingLoading = false;
const demoBillText = computed(() =>
  demoBilling.value === 'credits'
    ? '积分订阅（每轮按用量扣周池/加油包积分，钱包不动）'
    : demoBilling.value === 'wallet'
      ? '钱包按量（每轮按实际用量扣租户钱包，单位元）'
      : demoBilling.value === 'unknown'
        ? '按当前消费方式（具体方式暂未读到，可去「积分套餐」页确认）'
        : '获取中…'
);
watch(demoOpen, async (open) => {
  if (open && demoBilling.value == null && !demoBillingLoading) {
    demoBillingLoading = true;
    try {
      const res = (await getAssistantStatus()) as unknown as { data?: { billingMode?: string } };
      const d = unwrap<Record<string, unknown>>(res);
      demoBilling.value = d?.billingMode === 'credits' ? 'credits' : 'wallet';
    } catch {
      /* 状态不可达：给中性文案，不打断演示入口 */
      demoBilling.value = 'unknown';
    } finally {
      demoBillingLoading = false;
    }
  }
});

/** 演示剧本（业务批8 Q5：5 幕扩 9 幕小剧场；desc=「你会看到什么」结果导向。
 *  成员安全 5 幕=开场/附件/联网/第一分钟/交前自查（联网对成员无业务风险，与建议池同裁口径——
 *  仅剔除建任务/催办动作词） */
interface DemoScript {
  icon: string;
  title: string;
  desc: string;
  steps: string[];
  memberSafe: boolean;
}
const DEMO_SCRIPTS: DemoScript[] = [
  {
    icon: 'bot',
    title: '开场 30 秒',
    desc: '你会看到：助手自报三类本事，并当场示范一件',
    memberSafe: true,
    steps: ['先做个自我介绍：你是谁，能帮我做哪三类事？', '好，挑你说的第二类，现场来一个小例子']
  },
  {
    icon: 'clipboard',
    title: '信息采集一条龙',
    desc: '你会看到：一句话生成模板，再一句话建好任务',
    memberSafe: false,
    steps: [
      '帮我做一个办公用品需求采集模板，字段：姓名、工号、部门、办公区、房间号',
      '很好，再用它建一个填表任务，名称「办公用品需求填报」，截止时间下周五 18:00'
    ]
  },
  {
    icon: 'chart',
    title: '数据看结论',
    desc: '你会看到：进度查出来，还附一张图和一句人话',
    memberSafe: false,
    steps: ['我发布的任务提交进度怎么样？', '把进度最差的任务画一张图，并用一句话告诉我该先管哪个']
  },
  {
    icon: 'alarm',
    title: '逾期风险三连',
    desc: '你会看到：谁逾期、哪个部门最多、总结直接可转发',
    memberSafe: false,
    steps: ['我名下的任务有谁逾期了吗？', '逾期的人按部门排一下，哪个部门最多？', '把逾期情况总结成一句话，我要发给部门负责人']
  },
  {
    icon: 'globe',
    title: '联网查最新',
    desc: '你会看到：助手先开联网开关再作答（第一步有提示）',
    memberSafe: true,
    steps: [
      '帮我联网查一下最近一周数据安全相关的政策或新闻（请先点亮输入框旁的联网开关再继续）',
      '挑一条最重要的，用一句话告诉我它会怎么影响我们'
    ]
  },
  {
    icon: 'file',
    title: '附件读文档',
    desc: '你会看到：拖一份文件进来，三个要点立刻出来',
    memberSafe: true,
    steps: ['（先随便拖一份 Word 或 PDF 到输入框）我上传的文件讲了什么？给我三个要点', '把它压缩成一条通知的口吻，我要发给成员']
  },
  {
    icon: 'megaphone',
    title: '该催谁了',
    desc: '你会看到：催办候选排好序，草稿张口就来',
    memberSafe: false,
    steps: ['最近哪个任务该催了？', '就挑你排第一的那个，帮我起草一条催办提醒']
  },
  {
    icon: 'pencil',
    title: '成员第一分钟',
    desc: '你会看到：待办列出来，临期任务帮你标红',
    memberSafe: true,
    steps: ['我今天有哪些待办？', '其中哪些三天内要截止？']
  },
  {
    icon: 'check',
    title: '交前自查',
    desc: '你会看到：提交前助手先替你把表过一遍',
    memberSafe: true,
    steps: ['我要交表了，帮我检查一遍我的填报内容有没有明显问题', '把要改的地方列成清单，改完我再交']
  },
  // 批17 Q7：九幕扩十二幕——@引用/追加数据/业务知识三个此前没覆盖的日常场景
  {
    icon: 'at-sign',
    title: '@点名任务问',
    desc: '你会看到：@ 一个任务，问答就围着它转',
    memberSafe: true,
    steps: ['@追加测试 这个任务现在的整体提交情况怎么样？', '它和填表类任务比，管起来有什么不一样？一句话说清']
  },
  {
    icon: 'layers',
    title: '追加数据结论',
    desc: '你会看到：追加了多条记录，还能排出前三名',
    memberSafe: false,
    steps: ['追加测试任务到现在一共追加了多条记录？', '按追加条数排一下，最多的前三名成员是谁？']
  },
  {
    icon: 'book',
    title: '业务知识问不倒',
    desc: '你会看到：广播和普通任务的差别讲成大白话，还替你拿主意',
    memberSafe: true,
    steps: ['广播任务和普通任务到底差在哪？用大白话讲清楚', '我要发一个成员信息收集，选哪种合适？直接给建议']
  }
];
const demoList = computed(() => (roleKey.value === 'member' ? DEMO_SCRIPTS.filter((d) => d.memberSafe) : DEMO_SCRIPTS));

const seen = ref<Set<string>>(new Set());
const pool = ref<string[]>([]);
const shown = computed<SugItem[]>(() => pool.value.map((t) => SUGGESTIONS.value.find((s) => s[1] === t)!).filter(Boolean));

function draw4() {
  // 换一批扩池轮换去重；候选<4 时重置（v3.17 口径）；页面/身份切换后 seen 过滤失效项
  let candidates = SUGGESTIONS.value.filter((s) => !seen.value.has(s[1]));
  if (candidates.length < 4) {
    seen.value = new Set();
    candidates = [...SUGGESTIONS.value];
  }
  const picked = candidates.slice(0, 4).map((s) => s[1]);
  picked.forEach((t) => seen.value.add(t));
  pool.value = picked;
}

watch(SUGGESTIONS, () => {
  // 页面切换换池：重抽一次（seen 里不在新池的标题自然失效）
  draw4();
});

const greet = computed(() => {
  const name = useUserStore().nickName || useUserStore().name || '';
  // 有 lastAsk 召回=老朋友回访（原型：back 存在时不用首访问候）
  const first = props.newbieUse < 1 && !lastAsk.value;
  // 成员面彻底无催办词（v12:76 断言守护口径）
  const cap = roleKey.value === 'member' ? '数据查询、提交状态、操作指引' : '数据查询、催办提醒、操作指引';
  if (first) {
    const h = new Date().getHours();
    const word = h < 6 ? '夜深了' : h < 12 ? '早上好' : h < 14 ? '中午好' : h < 18 ? '下午好' : '晚上好';
    return { title: `${word}，我是 SmartDock 小助手`, sub: `${name ? name + '，' : ''}${cap}，直接开口问就行` };
  }
  const variants = ['又见面了，我是 SmartDock 小助手', '接着上次聊的？', '今天从哪开始？'];
  const title = variants[new Date().getDate() % 3];
  return { title, sub: roleKey.value === 'member' ? '查数据 · 讲操作' : '查数据 · 发催办 · 讲操作' };
});

function refresh() {
  draw4();
}
draw4();
</script>

<style lang="scss" scoped>
.dcs-hero {
  margin-top: 6px;
  /* 间距回修（2026-10-11 用户反馈「四卡与常用上下拥挤」）：紧凑视口/抽屉里
     欢迎卡底缘与下方「常用」行贴死——恒定 12px 底距兜底（宽屏大空档不受影响） */
  margin-bottom: 12px;
  animation: dcs-hero-pop 0.3s ease both;
}
.hero-card {
  border-radius: 14px;
  padding: 18px 14px 14px;
  background: linear-gradient(160deg, var(--el-color-primary-light-9), var(--el-bg-color) 62%);
  border: 1px solid var(--el-color-primary-light-8);
  text-align: center;
}
.snaprow {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  background: var(--el-bg-color);
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: 10px;
  padding: 7px 10px;
  margin-bottom: 10px;
  font-size: 11.5px;
  color: var(--dcs-text-secondary);

  .sn {
    background: var(--el-color-primary);
    color: #fff;
    border-radius: 6px;
    padding: 1px 8px;
    font-size: 10.5px;
    flex: none;
  }
  b {
    color: var(--el-color-primary);
    font-size: 13px;
  }
  .wn2 b {
    color: var(--el-color-warning);
  }
}
/* 原型 hero：orb 独行居中 + 问候/副文案居中单列 */
.hc-head {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  animation: dcs-hero-fade 0.45s ease both;

  .orb2 {
    width: 34px;
    height: 34px;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    color: #fff;
    background: linear-gradient(145deg, var(--el-color-primary), var(--el-color-primary-dark-2));
    box-shadow: 0 2px 6px rgb(64 158 255 / 35%);
    flex: none;
  }
  b {
    font-size: 16px;
    color: var(--dcs-text-primary);
  }
}
.sub {
  margin: 8px auto 0;
  font-size: 12.5px;
  color: var(--dcs-text-secondary);
  text-wrap: balance;
  animation: dcs-hero-fade 0.45s ease 0.05s both;
}
.newbie {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin: 12px 0 0;
  animation: dcs-hero-fade 0.45s ease 0.1s both;

  > span {
    font-size: 12px;
    color: var(--dcs-text-secondary);
  }
  button {
    border: 1px solid var(--el-color-primary-light-7);
    background: var(--el-bg-color);
    border-radius: 14px;
    padding: 4px 10px;
    font-size: 12px;
    color: var(--el-color-primary);
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    gap: 4px;

    &:hover {
      background: var(--el-color-primary-light-9);
      border-color: var(--el-color-primary-light-5);
    }
    &.link {
      border: none;
      background: none;
      padding: 4px 2px;
      text-decoration: underline;
      color: var(--dcs-text-secondary);
    }
  }
  &.gone {
    margin-top: 6px;
  }
}
/* R4b 演示模式：入口行常驻（含老用户态）；剧本卡与建议卡同构+s0 复用（企业风克制） */
.demo-row {
  margin-top: 6px;

  .link {
    border: none;
    background: none;
    color: var(--el-color-primary);
    font-size: 12px;
    cursor: pointer;
    padding: 2px 4px;
    display: inline-flex;
    align-items: center;
    gap: 4px;

    &:hover {
      text-decoration: underline;
    }
  }
}
.demo {
  margin-top: 8px;
  padding: 10px 12px;
  border: 1px solid #c6e2ff;
  background: #f7fbff;
  border-radius: 10px;
  animation: dcs-hero-fade 0.45s ease both;

  .demo-hd {
    /* step5 P1-1 回修：子元素（标题/结果承诺）必须纵排——横向 flex 会把标题挤压成逐字竖排不可读 */
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 2px;
    margin-bottom: 4px;
    font-size: 12px;
    color: var(--dcs-text-secondary);

    b {
      font-size: 13px;
      color: var(--dcs-text-primary);
    }

    /* step5 P2-D9：真实留痕披露句——比普通说明更醒目一点 */
    .demo-note {
      color: var(--el-color-warning);
    }
  }
  .demo-card {
    width: 100%;
    border: 1px solid var(--dcs-border-color-light);
    background: var(--el-bg-color);
    border-radius: 8px;
    padding: 8px 10px;
    margin-bottom: 6px;
    cursor: pointer;
    text-align: left;
    font: inherit;

    &:hover {
      border-color: var(--el-color-primary);
    }

    /* 业务批8 Q5：幕号圆标 */
    .demo-no {
      flex: none;
      min-width: 18px;
      height: 18px;
      border-radius: 6px;
      background: var(--dcs-bg-muted, #f4f4f5);
      color: var(--dcs-text-secondary);
      font-size: 11px;
      font-weight: 600;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      align-self: center;
    }

    .demo-play {
      flex: none;
      font-size: 11px;
      color: var(--el-color-primary);
      background: #ecf5ff;
      border-radius: 8px;
      padding: 1px 8px;
    }
  }
}
.sughd {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
  font-size: 12px;
  color: var(--dcs-text-secondary);
  animation: dcs-hero-fade 0.45s ease 0.15s both;

  .rfbtn {
    border: none;
    background: none;
    color: var(--el-color-primary);
    font-size: 12px;
    cursor: pointer;
    padding: 2px 4px;

    &:hover {
      text-decoration: underline;
    }
  }
}
/* 原型：抽屉=单列列表行（图标 tile 左，标题+补语右）；全屏 2 列 grid 由 .dcs-dw.full :deep 覆盖 */
.sug {
  display: grid;
  grid-template-columns: 1fr;
  gap: 10px;
  margin-top: 8px;
}
.s0 {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 10px;
  text-align: left;
  border: 1px solid var(--dcs-border-color-lighter);
  background: var(--el-bg-color);
  border-radius: 10px;
  padding: 9px 10px;
  cursor: pointer;
  animation: dcs-hero-up 0.35s ease both;

  &:nth-child(1) {
    animation-delay: 0.18s;
  }
  &:nth-child(2) {
    animation-delay: 0.24s;
  }
  &:nth-child(3) {
    animation-delay: 0.3s;
  }
  &:nth-child(4) {
    animation-delay: 0.36s;
  }

  &:hover {
    border-color: var(--el-color-primary-light-5);
    box-shadow: 0 2px 8px rgb(64 158 255 / 12%);
  }
  .s-ic {
    width: 26px;
    height: 26px;
    flex: none;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 6px;
    background: var(--el-color-primary-light-9);
    color: var(--el-color-primary);
    font-size: 14px;
    line-height: 1;
  }
  .s-txt {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
  }
  b {
    font-size: 13.5px;
    color: var(--dcs-text-primary);
    font-weight: 600;
    /* 业务批4 Q7-A（2026-10-13 拍板）：标题两行封顶（超出省略）——375px 下同排卡
     * 不再「一行截断 vs 换行」混排参差，信息量优先；line-clamp 标准属性双写（m3 P2-6，
     * 新 Firefox 走标准属性不依赖 webkit 前缀） */
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
  .s-sub {
    font-size: 11.5px;
    color: var(--dcs-text-secondary);
    /* 业务批4 Q7-A：副文案固定一行省略，卡片高度由标题两行封顶 */
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
}
.help-ul {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.9;
  color: var(--dcs-text-regular);
}
@keyframes dcs-hero-pop {
  from {
    opacity: 0;
    transform: scale(0.98) translateY(6px);
  }
}
@keyframes dcs-hero-fade {
  from {
    opacity: 0;
  }
}
@keyframes dcs-hero-up {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
}
@media (prefers-reduced-motion: reduce) {
  .dcs-hero,
  .hc-head,
  .sub,
  .newbie,
  .sughd,
  .s0 {
    animation: none;
  }
}
</style>
