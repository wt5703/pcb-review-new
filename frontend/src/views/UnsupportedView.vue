<script setup lang="ts">
defineProps<{ title: string; capability: string }>()
</script>

<template><div class="page-head"><div><h1>{{ title }}</h1><p>页面布局已按原型保留。</p></div></div><section class="card unsupported"><div class="unsupported-icon">⌁</div><h2>当前后端暂未提供{{ capability }}接口</h2><p>前端不会以静态演示数据替代真实业务数据。待后端补充查询接口后，此页面可直接接入 API。</p></section></template>
