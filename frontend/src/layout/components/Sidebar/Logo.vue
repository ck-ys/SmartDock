<template>
  <div class="sidebar-logo-container" :class="{ collapse: collapse }">
    <transition :enter-active-class="proxy?.animate.logoAnimate.enter" mode="out-in">
      <router-link v-if="collapse" key="collapse" class="sidebar-logo-link" to="/">
        <i class="dcs-mark sidebar-logo-mark" aria-hidden="true">S</i>
      </router-link>
      <router-link v-else key="expand" class="sidebar-logo-link" to="/">
        <i class="dcs-mark sidebar-logo-mark" aria-hidden="true">S</i>
        <h1 class="sidebar-title">
          {{ title }}
        </h1>
      </router-link>
    </transition>
  </div>
</template>

<script setup lang="ts">
const { proxy } = getCurrentInstance() as ComponentInternalInstance;

defineProps({
  collapse: {
    type: Boolean,
    required: true
  }
});

const title = import.meta.env.VITE_APP_LOGO_TITLE;
</script>

<style lang="scss" scoped>
.sidebarLogoFade-enter-active {
  transition: opacity 1.5s;
}

.sidebarLogoFade-enter,
.sidebarLogoFade-leave-to {
  opacity: 0;
}

.sidebar-logo-container {
  position: relative;
  height: var(--app-topbar-h);
  line-height: var(--app-topbar-h);
  /* 顶栏模式Logo坐在壳层上，底色交给容器（sheet/frame），不再按明暗各自取色 */
  background: transparent;
  text-align: center;
  overflow: hidden;

  & .sidebar-logo-link {
    height: 100%;
    width: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 9px;

    & .sidebar-logo-mark {
      width: 26px;
      height: 26px;
    }

    & .sidebar-title {
      display: inline-block;
      margin: 0;
      color: var(--app-text);
      font-weight: 600;
      line-height: var(--app-topbar-h);
      font-size: 14px;
      font-family:
        Avenir,
        Helvetica Neue,
        Arial,
        Helvetica,
        sans-serif;
      vertical-align: middle;
    }
  }

  &.collapse {
    .sidebar-logo-mark {
      margin-right: 0px;
    }
  }
}
</style>
