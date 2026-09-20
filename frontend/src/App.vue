<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'
import { reviewApi } from '@/api/review-api'
import { identity, roleList, saveIdentity } from '@/stores/identity'

const route = useRoute()
const settingsOpen = ref(false)
const mockUsers = ref<Array<{ id: number; displayName: string; email: string; departmentName: string; roles: string[] }>>([])
const roleOptions = [
  'HARDWARE_DEPARTMENT_MANAGER', 'PCB_LEADER', 'SCHEMATIC_LEADER', 'HARDWARE_EXPERT',
  'EMC_EXPERT', 'DESIGNER', 'PROCESS_EXPERT', 'STRUCTURE_EXPERT'
]
const pageTitle = computed(() => String(route.meta.title ?? 'BMS PCB 评审平台'))

function persistIdentity(): void {
  saveIdentity()
  settingsOpen.value = false
}

function selectMockUser(): void {
  const user = mockUsers.value.find((item) => item.id === identity.userId)
  if (user) identity.roles = user.roles.join(',')
}

const currentMockUser = computed(() => mockUsers.value.find((item) => item.id === identity.userId))

onMounted(async () => {
  try {
    mockUsers.value = await reviewApi.listMockUsers()
    selectMockUser()
  } catch {
    mockUsers.value = []
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
        <RouterLink to="/emails" class="nav-item"><span>✉</span>邮件记录</RouterLink>
        <RouterLink to="/users" class="nav-item"><span>♙</span>用户管理</RouterLink>
      </nav>
      <div class="sidebar-foot">Local Mock<br><b>API 已连接</b></div>
    </aside>
    <main class="main-area">
      <header class="topbar">
        <div><span class="breadcrumb">BMS PCB 评审平台</span><b>{{ pageTitle }}</b></div>
        <div class="identity-control">
          <button class="identity-button" type="button" @click="settingsOpen = !settingsOpen">
            <span class="avatar">{{ identity.userId }}</span>
            <span>{{ currentMockUser?.displayName || `Mock 用户 #${identity.userId}` }}</span><span class="chevron">⌄</span>
          </button>
          <section v-if="settingsOpen" class="identity-panel card" aria-label="本地 Mock 身份设置">
            <h3>本地 Mock 身份</h3>
            <p>选择初始化账号后，请求会自动携带对应的用户 ID 与预设角色。</p>
            <label>初始化账号
              <select v-if="mockUsers.length" v-model.number="identity.userId" @change="selectMockUser">
                <option v-for="user in mockUsers" :key="user.id" :value="user.id">{{ user.displayName }} · {{ user.departmentName }}</option>
              </select>
              <input v-else v-model.number="identity.userId" type="number" min="1" />
            </label>
            <small v-if="currentMockUser">{{ currentMockUser.email }}</small>
            <label>角色
              <select v-model="identity.roles">
                <option v-for="role in roleOptions" :key="role" :value="role">{{ role }}</option>
              </select>
            </label>
            <small>当前：{{ roleList.join(', ') }}</small>
            <button class="btn primary" type="button" @click="persistIdentity">保存身份</button>
          </section>
        </div>
      </header>
      <section class="page-content"><RouterView /></section>
    </main>
  </div>
</template>
