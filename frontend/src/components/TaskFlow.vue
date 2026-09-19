<script setup lang="ts">
defineProps<{ status: string; reviewType: string }>()

const pcbSteps = [
  ['DRAFT', '创建任务'], ['PCB_PENDING_REVIEW', '专家分配'], ['PCB_EXPERT_REVIEWING', '专家评审'],
  ['PCB_OPTIONAL_REVIEWING', '工艺/结构'], ['PENDING_MUTUAL_ASSIGNMENT', '互检分配'], ['MUTUAL_REVIEWING', '互检'],
  ['PENDING_FINISH_CONFIRMATION', '结束确认'], ['FINISHED', '完成']
]
const schematicSteps = [
  ['DRAFT', '创建任务'], ['SCHEMATIC_PENDING_MUTUAL_ASSIGNMENT', '互检分配'], ['MUTUAL_REVIEWING', '互检'],
  ['SCHEMATIC_PENDING_REVIEW', '专家分配'], ['HARDWARE_REVIEWING', '硬件评审'], ['PENDING_FINISH_CONFIRMATION', '结束确认'], ['FINISHED', '完成']
]
</script>

<template>
  <div class="flow-steps">
    <template v-for="([key, label], index) in reviewType === 'PCB' ? pcbSteps : schematicSteps" :key="key">
      <div class="flow-step" :class="{ current: key === status, done: (reviewType === 'PCB' ? pcbSteps : schematicSteps).findIndex((item) => item[0] === status) > index }">
        <span class="step-dot">{{ index + 1 }}</span><span>{{ label }}</span>
      </div>
    </template>
  </div>
</template>
