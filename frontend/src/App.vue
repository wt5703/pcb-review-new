<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'
import { reviewApi } from '@/api/review-api'
import { identity } from '@/stores/identity'

const route = useRoute()
const pageTitle = computed(() => String(route.meta.title ?? 'BMS PCB 评审平台'))

onMounted(async () => {
  try {
    const currentUser = await reviewApi.getCurrentUser()
    identity.employeeNo = currentUser.employeeNo
    identity.displayName = currentUser.displayName || currentUser.employeeNo
    identity.email = currentUser.email || ''
    identity.mobile = currentUser.mobile || ''
    identity.departmentName = currentUser.departmentName || ''
    identity.roles = currentUser.roles.join(',')
  } catch {
    // 用户中心或后端不可用时保留本地 Mock 身份，便于开发环境继续使用。
  }
})
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <RouterLink to="/" class="brand">
        <span class="brand-mark">B</span>
        <span>BMS PCB 评审平台<small>PCB / 原理图协同评审</small></span>
      </RouterLink>
      <nav class="side-nav" aria-label="主导航">
        <RouterLink to="/" class="nav-item"><span>⌂</span>工作台</RouterLink>
        <RouterLink to="/tasks" class="nav-item"><span>▤</span>任务列表</RouterLink>
        <RouterLink to="/my-tasks" class="nav-item"><span>◷</span>我的任务</RouterLink>
        <RouterLink to="/templates" class="nav-item"><span>☷</span>互检管理</RouterLink>
        <RouterLink to="/reviewer-whitelists" class="nav-item"><span>♙</span>评审白名单管理</RouterLink>
      </nav>
      <div class="sidebar-foot">当前用户<br><b>{{ identity.employeeNo }}</b></div>
    </aside>
    <main class="main-area">
      <header class="topbar">
        <div><span class="breadcrumb">BMS PCB 评审平台</span><b>{{ pageTitle }}</b></div>
        <div class="identity-control"><span class="identity-button"><span class="avatar">{{ identity.displayName.slice(0, 1) }}</span><span>{{ identity.displayName }}（{{ identity.employeeNo }}）</span></span></div>
      </header>
      <section class="page-content"><RouterView /></section>
    </main>
  </div>
</template>
