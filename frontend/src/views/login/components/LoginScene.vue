<template>
  <div class="login-scene">
    <span class="scene-grid" aria-hidden="true"></span>
    <span class="scene-glow" aria-hidden="true"></span>

    <header class="scene-brand">
      <div class="scene-brand__word">SmartDock</div>
      <p class="scene-brand__tagline">用户画像与 AI 助手的工作台</p>
      <ul class="scene-brand__caps">
        <li><b>画像</b><span>评分 · 分群</span></li>
        <li><b>助手</b><span>查数 · 确认</span></li>
        <li><b>分析</b><span>会话 · 漏斗</span></li>
      </ul>
    </header>

    <div class="product-stage">
      <div class="product-window" aria-hidden="true">
        <div class="pw-bar"><i></i><i></i><i></i><span>SmartDock · 画像工作台</span></div>
        <div class="pw-main">
          <aside class="pw-side">
            <b>工作台</b>
            <span class="is-on">主体总览</span>
            <span>分群管理</span>
            <span>低分复核</span>
            <span>评分模型</span>
            <span>事件账本</span>
          </aside>
          <div class="pw-content">
            <div class="pw-head">
              <span class="pw-avatar">陈</span>
              <div class="pw-person">
                <b>陈××</b>
                <small>最近活跃：今天 09:42 · 来源：官网</small>
              </div>
              <div class="pw-score"><b>82</b><span>综合评分</span></div>
            </div>
            <div class="pw-tags"><i>近 30 天活跃</i><i>高价值</i><i>华东</i><i>已绑定账号</i></div>
            <div class="pw-cols">
              <div class="pw-panel">
                <div class="pw-panel__title">画像趋势<span>近 7 天</span></div>
                <div class="pw-bars">
                  <i style="height: 38%"></i><i style="height: 56%"></i><i style="height: 46%"></i> <i style="height: 72%"></i
                  ><i style="height: 61%"></i><i style="height: 84%"></i>
                  <i style="height: 76%"></i>
                </div>
                <div class="pw-axis"><span>周一</span><span>周三</span><span>周五</span><span>周日</span></div>
              </div>
              <div class="pw-panel">
                <div class="pw-panel__title">最近事件<span>实时</span></div>
                <div class="pw-events">
                  <div class="pw-event"><em>09:42</em><span>查看了「方案对比」页面</span></div>
                  <div class="pw-event"><em>昨天</em><span>完成一次会话 · 6m12s</span></div>
                  <div class="pw-event"><em>10-18</em><span>进入高价值分群</span></div>
                  <div class="pw-event"><em>10-17</em><span>提交了一条咨询</span></div>
                  <div class="pw-event"><em>10-16</em><span>从匿名身份完成绑定</span></div>
                  <div class="pw-event"><em>10-15</em><span>收藏了「解决方案」页面</span></div>
                  <div class="pw-event"><em>10-14</em><span>评分从 78 升到 82</span></div>
                  <div class="pw-event"><em>10-12</em><span>加入「近 30 天活跃」分群</span></div>
                </div>
              </div>
            </div>
            <div class="pw-foot"><span>数据更新于 2 分钟前 · 模型 v3</span><b>可信度 96%</b></div>
          </div>
        </div>
      </div>

      <div class="assistant-float" aria-hidden="true">
        <div class="assistant-float__head"><b>AI 助手</b><span>画像查询</span><i></i></div>
        <div class="assistant-float__msg is-user">帮我圈出近 30 天没互动的客户</div>
        <div class="assistant-float__msg is-ai">已找到 12 人，确认后生成名单</div>
        <div class="assistant-float__acts"><span>调整条件</span><b>确认生成</b></div>
      </div>
    </div>

    <div class="scene-caps" aria-hidden="true">
      <span><b>画像</b> 评分 · 分群 · 复核</span>
      <span><b>助手</b> 对话 · 人工确认</span>
      <span><b>分析</b> 会话 · 漏斗</span>
    </div>
    <p class="scene-note">界面示意 · 数据为演示</p>
  </div>
</template>

<style lang="scss" scoped>
.login-scene {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  background:
    radial-gradient(70% 55% at 18% 8%, rgba(64, 128, 230, 0.24), transparent 62%),
    radial-gradient(55% 45% at 92% 92%, rgba(61, 218, 215, 0.1), transparent 62%), linear-gradient(158deg, #061126, #081c3c 55%, #0a2450);
  color: #f4f8ff;
}

.scene-grid {
  position: absolute;
  inset: 0;
  background-image: radial-gradient(rgba(140, 190, 255, 0.12) 1px, transparent 1px);
  background-size: 26px 26px;
  mask-image: radial-gradient(90% 80% at 45% 55%, #000 5%, transparent 78%);
  -webkit-mask-image: radial-gradient(90% 80% at 45% 55%, #000 5%, transparent 78%);
  pointer-events: none;
}

.scene-glow {
  position: absolute;
  left: 14%;
  top: 18%;
  width: 66%;
  height: 52%;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(64, 128, 230, 0.28), transparent 66%);
  filter: blur(28px);
  pointer-events: none;
}

.scene-brand {
  position: absolute;
  left: 48px;
  top: 44px;
  right: 48px;
  z-index: 3;
}
.scene-brand__word {
  font-size: 38px;
  font-weight: 800;
  line-height: 1;
  letter-spacing: 0.02em;
}
.scene-brand__tagline {
  margin: 10px 0 0;
  color: #9db9dd;
  font-size: 13px;
  letter-spacing: 0.06em;
}
.scene-brand__caps {
  display: flex;
  gap: 18px;
  margin: 14px 0 0;
  padding: 0;
  list-style: none;
  font-size: 10px;
  color: #9fbcdd;
  li {
    display: flex;
    gap: 5px;
    white-space: nowrap;
  }
  b {
    color: #eaf4ff;
    font-weight: 600;
  }
}

.product-stage {
  position: absolute;
  left: 48px;
  top: 190px;
  z-index: 2;
  width: 84%;
  max-width: 800px;
  aspect-ratio: 608 / 410;
}

.product-window {
  position: relative;
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  overflow: hidden;
  border: 1px solid rgba(180, 215, 255, 0.35);
  border-radius: 12px;
  background: #fff;
  box-shadow:
    0 44px 90px -34px rgba(0, 0, 0, 0.78),
    inset 0 1px 0 rgba(255, 255, 255, 0.5);
}

.pw-bar {
  display: flex;
  flex: none;
  align-items: center;
  gap: 5px;
  height: 30px;
  padding: 0 12px;
  background: #0b1b33;
  color: #8fb0d8;
  i {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: #36507a;
  }
  i:nth-child(1) {
    background: #ff6b6b;
  }
  i:nth-child(2) {
    background: #f5b942;
  }
  i:nth-child(3) {
    background: #3ddad7;
  }
  span {
    margin-left: 8px;
    font-size: 9px;
    letter-spacing: 0.04em;
  }
}

.pw-main {
  display: flex;
  flex: 1;
  min-height: 0;
  background: #f6f8fc;
  color: #1b2a4a;
}

.pw-side {
  flex: none;
  width: 96px;
  padding: 12px 9px;
  border-right: 1px solid #e6ecf7;
  background: #fff;
  font-size: 9px;
  color: #66718c;
  b {
    display: block;
    margin-bottom: 9px;
    color: #08184f;
    font-size: 9.5px;
  }
  span {
    display: flex;
    align-items: center;
    gap: 4px;
    margin-bottom: 2px;
    padding: 4px 6px;
    border-radius: 4px;
    &.is-on {
      background: #eef4ff;
      color: #2b56a8;
      font-weight: 600;
    }
    &::before {
      content: '';
      width: 4px;
      height: 4px;
      border-radius: 1px;
      background: currentColor;
      opacity: 0.45;
    }
  }
}

.pw-content {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  padding: 12px 14px;
}

.pw-head {
  display: flex;
  align-items: center;
  gap: 9px;
}
.pw-avatar {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: linear-gradient(135deg, #2f5fd0, #3ddad7);
  color: #fff;
  font-size: 11px;
  font-weight: 700;
}
.pw-person {
  min-width: 0;
  b {
    display: block;
    color: #08184f;
    font-size: 12px;
  }
  small {
    display: block;
    margin-top: 1px;
    overflow: hidden;
    color: #66718c;
    font-size: 8px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}
.pw-score {
  flex: none;
  margin-left: auto;
  text-align: right;
  b {
    display: block;
    color: #167a54;
    font-size: 20px;
    line-height: 1;
  }
  span {
    font-size: 7px;
    color: #66718c;
  }
}
.pw-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 9px;
  i {
    padding: 1px 6px;
    border: 1px solid #dbe6f7;
    border-radius: 999px;
    background: #fff;
    color: #33507f;
    font-size: 7.5px;
    font-style: normal;
  }
}
.pw-cols {
  display: grid;
  flex: 1;
  grid-template-columns: 1.05fr 0.95fr;
  gap: 10px;
  min-height: 0;
  margin-top: 10px;
}
.pw-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 9px;
  border: 1px solid #e6ecf7;
  border-radius: 7px;
  background: #fff;
}
.pw-panel__title {
  display: flex;
  justify-content: space-between;
  color: #08184f;
  font-size: 8.5px;
  font-weight: 600;
  span {
    color: #8a93a8;
    font-weight: 400;
  }
}
.pw-bars {
  display: flex;
  flex: 1;
  align-items: flex-end;
  gap: 6px;
  min-height: 46px;
  margin-top: 6px;
  i {
    flex: 1;
    border-radius: 2px 2px 0 0;
    background: linear-gradient(180deg, #4d86e8, #2f5fd0);
    opacity: 0.85;
    &:nth-child(2n) {
      background: linear-gradient(180deg, #6ccfc9, #2f8f8a);
      opacity: 0.6;
    }
  }
}
.pw-axis {
  display: flex;
  justify-content: space-between;
  margin-top: 3px;
  color: #9aa4b8;
  font-size: 7px;
}
.pw-events {
  display: grid;
  align-content: start;
  gap: 3px;
  margin-top: 5px;
}
.pw-event {
  display: flex;
  gap: 5px;
  font-size: 8px;
  color: #4a5878;
  em {
    flex: none;
    color: #9aa4b8;
    font-style: normal;
    font-size: 7px;
  }
  span {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}
.pw-foot {
  display: flex;
  justify-content: space-between;
  margin-top: auto;
  padding-top: 6px;
  border-top: 1px solid #e6ecf7;
  color: #8a93a8;
  font-size: 7.5px;
  b {
    color: #167a54;
    font-weight: 600;
  }
}

.assistant-float {
  position: absolute;
  right: -20px;
  bottom: -24px;
  z-index: 4;
  width: min(300px, 40%);
  padding: 12px 13px;
  border: 1px solid rgba(150, 195, 255, 0.28);
  border-radius: 11px;
  background: rgba(9, 27, 53, 0.93);
  box-shadow:
    0 26px 55px -22px rgba(0, 0, 0, 0.85),
    inset 0 1px 0 rgba(220, 240, 255, 0.14);
  backdrop-filter: blur(10px);
  color: #d8e8fb;
}
.assistant-float__head {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #9db9dd;
  font-size: 9.5px;
  letter-spacing: 0.05em;
  b {
    color: #eef6ff;
    font-size: 10.5px;
    letter-spacing: 0.02em;
  }
  i {
    width: 5px;
    height: 5px;
    margin-left: auto;
    border-radius: 50%;
    background: #3ddad7;
    box-shadow: 0 0 8px #3ddad7;
  }
}
.assistant-float__msg {
  margin-top: 8px;
  padding: 5px 8px;
  border-radius: 6px;
  font-size: 9.5px;
  line-height: 1.5;
  &.is-user {
    margin-left: 16%;
    border: 1px solid rgba(160, 200, 255, 0.14);
    background: rgba(255, 255, 255, 0.08);
  }
  &.is-ai {
    border: 1px solid rgba(61, 218, 215, 0.25);
    background: rgba(61, 218, 215, 0.12);
  }
}
.assistant-float__acts {
  display: flex;
  gap: 6px;
  margin-top: 8px;
  span,
  b {
    flex: 1;
    padding: 4px 0;
    border-radius: 4px;
    text-align: center;
    font-size: 8.5px;
  }
  span {
    border: 1px solid rgba(160, 200, 255, 0.2);
    color: #9db9dd;
  }
  b {
    background: #2f5fd0;
    color: #fff;
  }
}

.scene-caps {
  position: absolute;
  left: 48px;
  bottom: 36px;
  z-index: 3;
  display: flex;
  gap: 18px;
  color: #9fbcdd;
  font-size: 10px;
  b {
    color: #e2f0ff;
    font-weight: 600;
  }
}
.scene-note {
  position: absolute;
  right: 48px;
  bottom: 26px;
  z-index: 3;
  margin: 0;
  color: #66809f;
  font-size: 8px;
}

@media (min-width: 1600px) {
  .scene-brand__word {
    font-size: 44px;
  }
  .scene-brand__tagline {
    font-size: 14px;
  }
  .scene-brand__caps {
    font-size: 10.5px;
  }
  .product-stage {
    top: 50%;
    max-width: 920px;
    transform: translateY(-50%);
  }
  .scene-caps {
    font-size: 10.5px;
  }
}

@media (max-width: 1279px) {
  .scene-brand {
    left: 32px;
    top: 32px;
    right: 32px;
  }
  .scene-brand__word {
    font-size: 32px;
  }
  .scene-brand__tagline {
    font-size: 11px;
  }
  .scene-brand__caps {
    gap: 14px;
    margin-top: 11px;
    font-size: 8.5px;
  }
  .product-stage {
    left: 32px;
    // 1024×768 下固定 152px 会让窗口下方空出 200px+，按视口高度收一档
    top: clamp(168px, 24vh, 190px);
    width: 88%;
    max-width: none;
  }
  .pw-side {
    display: none;
  }
  .pw-event:nth-child(n + 7) {
    display: none;
  }
  .assistant-float {
    right: -8px;
    bottom: -20px;
    width: min(230px, 42%);
  }
  .scene-caps {
    left: 32px;
    bottom: 24px;
    gap: 14px;
    font-size: 8.5px;
  }
  .scene-note {
    right: 32px;
    bottom: 18px;
    font-size: 7.5px;
  }
}

@media (max-width: 1000px) {
  .scene-brand {
    left: 20px;
    top: 20px;
    right: 20px;
  }
  .scene-brand__word {
    font-size: 28px;
  }
  .scene-brand__tagline {
    margin-top: 6px;
    font-size: 10px;
  }
  .scene-brand__caps,
  .product-stage,
  .scene-caps,
  .scene-note {
    display: none;
  }
}
</style>
