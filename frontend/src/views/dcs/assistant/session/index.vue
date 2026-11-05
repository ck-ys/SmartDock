<template>
  <AssistantWorkspace class="app-container" />
</template>

<script setup lang="ts">
/**
 * AI 助手会话页（批3.5·题2 起为薄壳）：业务壳内包「会话工作区公共积木」。
 * class="app-container" 透传到积木根元素——与搬运前同一个 DOM，页面行为零变化。
 */
import AssistantWorkspace from '@/components/DcsAssistant/AssistantWorkspace.vue';
</script>
