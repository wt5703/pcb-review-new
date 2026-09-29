<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'

const route = useRoute()
const pageTitle = computed(() => String(route.meta.title ?? 'BMS PCB 评审平台'))
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
      <div class="sidebar-foot">用户中心<br><b>Mock 当前用户</b></div>
    </aside>
    <main class="main-area">
      <header class="topbar">
        <div><span class="breadcrumb">BMS PCB 评审平台</span><b>{{ pageTitle }}</b></div>
        <div class="identity-control"><span class="identity-button"><span class="avatar">王</span><span>王鹏飞（用户中心 Mock）</span></span></div>
      </header>
      <section class="page-content"><RouterView /></section>
    </main>
  </div>
</template>
