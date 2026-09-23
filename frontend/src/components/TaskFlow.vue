<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ status: string; reviewType: string }>()
const pcbSteps = ['草稿', '专家评审', '工艺/结构评审', '互检单待分配', '互检单评审', '结束']
const schematicSteps = ['草稿', '互检单待分配', '互检单评审', '待分配硬件专家', '原理图评审', '结束']
const pcbActiveStep = computed(() => ({
  DRAFT: 1, PCB_EXPERT_REVIEWING: 2, PCB_PROCESS_STRUCTURE_REVIEWING: 3,
  MUTUAL_CHECK_PENDING_ASSIGNMENT: 4, MUTUAL_CHECK_REVIEWING: 5, FINISHED: 6
}[props.status] ?? 1))
const schematicActiveStep = computed(() => ({
  DRAFT: 1, MUTUAL_CHECK_PENDING_ASSIGNMENT: 2, MUTUAL_CHECK_REVIEWING: 3,
  SCHEMATIC_PENDING_HARDWARE_EXPERT_ASSIGNMENT: 4, SCHEMATIC_REVIEWING: 5, FINISHED: 6
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
