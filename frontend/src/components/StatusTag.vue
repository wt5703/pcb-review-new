<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ value: string }>()
const label = computed(() => ({
  DRAFT: '草稿', PCB_PENDING_REVIEW: '待分配专家', PCB_EXPERT_REVIEWING: '专家评审',
  PCB_OPTIONAL_REVIEWING: '工艺/结构评审', PENDING_MUTUAL_ASSIGNMENT: '待分配互检', MUTUAL_REVIEWING: '互检中',
  SCHEMATIC_PENDING_MUTUAL_ASSIGNMENT: '待分配互检', SCHEMATIC_PENDING_REVIEW: '待分配专家',
  HARDWARE_REVIEWING: '硬件评审', PENDING_FINISH_CONFIRMATION: '待结束确认', FINISHED: '已结束',
  PENDING_REPLY: '待答复', PENDING_CONFIRMATION: '待确认', CONFIRMED_PASS: '确认通过', WITHDRAWN: '已撤回',
  SUCCESS: '已发送', FAILED: '发送失败', PASS: '合格', FAIL: '不合格', NOT_APPLICABLE: '不适用'
} as Record<string, string>)[props.value] ?? props.value)
const tone = computed(() => {
  if (['FINISHED', 'CONFIRMED_PASS', 'SUCCESS', 'PASS'].includes(props.value)) return 'success'
  if (['FAIL', 'FAILED'].includes(props.value)) return 'danger'
  if (['PENDING_REPLY', 'PENDING_CONFIRMATION', 'PENDING_FINISH_CONFIRMATION'].includes(props.value)) return 'warning'
  if (props.value === 'DRAFT' || props.value === 'WITHDRAWN') return 'muted'
  return 'primary'
})
</script>

<template><span class="tag" :class="tone">{{ label }}</span></template>
