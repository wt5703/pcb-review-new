<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ value: string }>()
const label = computed(() => ({
  DRAFT: '草稿', PCB_EXPERT_REVIEWING: '专家评审', PCB_PROCESS_STRUCTURE_REVIEWING: '工艺/结构评审',
  MUTUAL_CHECK_PENDING_ASSIGNMENT: '互检单待分配', MUTUAL_CHECK_REVIEWING: '互检单评审',
  SCHEMATIC_PENDING_HARDWARE_EXPERT_ASSIGNMENT: '待分配硬件专家', SCHEMATIC_REVIEWING: '原理图评审', FINISHED: '结束',
  PENDING_REPLY: '待答复', PENDING_CONFIRMATION: '待确认', CONFIRMED_PASS: '确认通过', CONFIRMED_REJECTED: '确认不通过', WITHDRAWN: '已撤回',
  SUCCESS: '已发送', FAILED: '发送失败', PASS: '合格', FAIL: '不合格', NC: 'NC'
} as Record<string, string>)[props.value] ?? props.value)
const tone = computed(() => {
  if (['FINISHED', 'CONFIRMED_PASS', 'SUCCESS', 'PASS'].includes(props.value)) return 'success'
  if (['FAIL', 'FAILED', 'CONFIRMED_REJECTED'].includes(props.value)) return 'danger'
  if (['PENDING_REPLY', 'PENDING_CONFIRMATION', 'MUTUAL_CHECK_PENDING_ASSIGNMENT', 'SCHEMATIC_PENDING_HARDWARE_EXPERT_ASSIGNMENT'].includes(props.value)) return 'warning'
  if (props.value === 'DRAFT' || props.value === 'WITHDRAWN') return 'muted'
  return 'primary'
})
</script>

<template><span class="tag" :class="tone">{{ label }}</span></template>
