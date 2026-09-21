<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ status: string; reviewType: string }>()
const pcbSteps = ['创建', '专家评审', '设计者答复', '上传工艺/结构图', '工艺评审', '结构评审', '设计者答复', '互检分配', '互检', '设计者答复', '结束']
const schematicSteps = [
  '创建', '互检分配', '互检', '设计者答复', '硬件专家分配', '原理图评审', '设计者答复', '结束'
]
const pcbActiveStep = computed(() => ({
  DRAFT: 1, PCB_PENDING_REVIEW: 2, PCB_EXPERT_REVIEWING: 2, PCB_DESIGNER_REPLYING: 3, PCB_OPTIONAL_REVIEWING: 5,
  PCB_OPTIONAL_DESIGNER_REPLYING: 7, PENDING_MUTUAL_ASSIGNMENT: 8, MUTUAL_REVIEWING: 9, PCB_MUTUAL_DESIGNER_REPLYING: 10, PENDING_FINISH_CONFIRMATION: 10, FINISHED: 11
}[props.status] ?? 1))
const schematicActiveStep = computed(() => ({
  DRAFT: 1, SCHEMATIC_PENDING_LEADER_ASSIGNMENT: 2, SCHEMATIC_PENDING_MUTUAL_ASSIGNMENT: 2, MUTUAL_REVIEWING: 3,
  SCHEMATIC_MUTUAL_DESIGNER_REPLYING: 4, SCHEMATIC_PENDING_REVIEW: 5, HARDWARE_REVIEWING: 6,
  SCHEMATIC_DESIGNER_REPLYING: 7, PENDING_FINISH_CONFIRMATION: 7, FINISHED: 8
}[props.status] ?? 1))
</script>

<template>
  <div v-if="reviewType === 'PCB'" class="flow-steps pcb-flow">
    <template v-for="(label, index) in pcbSteps" :key="`${index}-${label}`">
      <div class="flow-step" :class="{ current: pcbActiveStep === index + 1, done: pcbActiveStep > index + 1 }">
        <span class="step-dot">{{ index + 1 }}</span><span>{{ label }}</span>
      </div>
    </template>
  </div>
  <div v-else class="flow-steps">
    <template v-for="(label, index) in schematicSteps" :key="`${index}-${label}`">
      <div class="flow-step" :class="{ current: schematicActiveStep === index + 1, done: schematicActiveStep > index + 1 }">
        <span class="step-dot">{{ index + 1 }}</span><span>{{ label }}</span>
      </div>
    </template>
  </div>
</template>
