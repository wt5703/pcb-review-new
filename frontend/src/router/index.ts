import { createRouter, createWebHistory } from 'vue-router'
import DashboardView from '@/views/DashboardView.vue'
import MyTasksView from '@/views/MyTasksView.vue'
import TaskCreateView from '@/views/TaskCreateView.vue'
import TaskDetailView from '@/views/TaskDetailView.vue'
import TaskListView from '@/views/TaskListView.vue'
import TemplateView from '@/views/TemplateView.vue'
import UnsupportedView from '@/views/UnsupportedView.vue'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'dashboard', component: DashboardView, meta: { title: '工作台' } },
    { path: '/tasks', name: 'tasks', component: TaskListView, meta: { title: '任务列表' } },
    { path: '/tasks/create', name: 'task-create', component: TaskCreateView, meta: { title: '创建评审任务' } },
    { path: '/tasks/:taskId/edit', name: 'task-edit', component: TaskCreateView, props: true, meta: { title: '编辑评审任务' } },
    { path: '/tasks/:taskId', name: 'task-detail', component: TaskDetailView, props: true, meta: { title: '任务详情' } },
    { path: '/my-tasks', name: 'my-tasks', component: MyTasksView, meta: { title: '我的任务' } },
    { path: '/templates', name: 'templates', component: TemplateView, meta: { title: '互检管理' } },
    { path: '/emails', name: 'emails', component: UnsupportedView, props: { title: '邮件记录', capability: '全局邮件记录查询' } },
    { path: '/users', name: 'users', component: UnsupportedView, props: { title: '用户管理', capability: '用户目录管理' } },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ]
})
