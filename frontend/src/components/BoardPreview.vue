<script setup lang="ts">
defineProps<{
  title: string
  fileName?: string
}>()
</script>

<template>
  <section class="board-preview" aria-label="PCB 图纸预览占位区">
    <header>
      <div>
        <b>{{ title }}</b>
        <small>{{ fileName || '当前任务文件预览' }}</small>
      </div>
      <span class="preview-tag">图纸预览</span>
    </header>
    <div class="board-canvas">
      <div class="board-grid" />
      <span class="board-label">BMS · PCB REVIEW</span>
      <i class="trace trace-a" /><i class="trace trace-b" /><i class="trace trace-c" /><i class="trace trace-d" />
      <div class="chip chip-a">MCU</div><div class="chip chip-b">CAN</div><div class="chip chip-c">PWR</div>
      <div class="review-mark">问题区域</div>
    </div>
    <footer>当前后端尚未提供文件预览二进制接口；该画布用于保持与原型一致的评审工作台结构。</footer>
  </section>
</template>

<style scoped>
.board-preview{border:1px solid #e6e6ef;border-radius:12px;background:#fff;overflow:hidden;margin-bottom:18px}.board-preview header{height:56px;display:flex;align-items:center;justify-content:space-between;padding:0 18px;border-bottom:1px solid #eeeef4}.board-preview b{display:block;color:#26213a}.board-preview small{display:block;margin-top:3px;color:#999;font-size:12px}.preview-tag{padding:4px 9px;border-radius:12px;background:#f1edff;color:#7255d8;font-size:12px}.board-canvas{position:relative;height:230px;margin:16px;border-radius:9px;overflow:hidden;background:linear-gradient(145deg,#0b4238,#124b3e 48%,#0c382f);box-shadow:inset 0 0 0 2px #1a6c58}.board-grid{position:absolute;inset:0;background-image:linear-gradient(rgba(238,184,61,.27) 1px,transparent 1px),linear-gradient(90deg,rgba(238,184,61,.27) 1px,transparent 1px);background-size:14px 14px}.board-label{position:absolute;left:19px;top:15px;color:#e4ba61;letter-spacing:2px;font:700 12px Arial}.trace{position:absolute;height:2px;background:#efba49;transform-origin:left center;box-shadow:0 0 5px rgba(239,186,73,.45)}.trace-a{width:210px;left:52px;top:86px;transform:rotate(11deg)}.trace-b{width:170px;left:258px;top:150px;transform:rotate(-28deg)}.trace-c{width:185px;left:94px;top:178px;transform:rotate(-6deg)}.trace-d{width:125px;right:64px;top:62px;transform:rotate(42deg)}.chip{position:absolute;display:grid;place-items:center;border:2px solid #d4a846;background:#172e2b;color:#e6c467;font:bold 10px Arial;box-shadow:0 0 0 5px rgba(216,168,65,.12)}.chip-a{left:43%;top:35%;width:76px;height:56px}.chip-b{right:20%;bottom:22%;width:48px;height:42px}.chip-c{left:15%;bottom:22%;width:46px;height:33px}.review-mark{position:absolute;right:27%;top:19%;padding:6px 9px;border:1px solid #ff8d8d;border-radius:4px;background:rgba(145,25,34,.75);color:#fff;font-size:12px}.board-preview footer{padding:0 18px 15px;color:#999;font-size:12px;line-height:1.5}@media(max-width:760px){.board-canvas{height:180px}.trace-c{width:110px}.chip-a{left:40%}.review-mark{right:12%}}
</style>
