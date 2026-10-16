<template>

  <YdPageHeader title="服务器列表" class="mb-0" />
  <YdPageMain>  <section class="mc-page">
    <section class="mc-hero">
      <div><h2>{{ model.closedSurface ? '已关闭服务器' : 'MC 服务器' }}</h2><p>{{ model.closedSurface ? '这里保留已关闭服务器的历史信息。' : '查看服务器在线状态、线路地址和当前周目。' }}</p></div>
      <div class="mc-actions"><YdButton v-if="!model.closedSurface" as="a" variant="outline" href="/platform/plugins/minecraft-server?closed=true"><YdIcon name="i-ri:archive-line" />更多服务器（已关闭）</YdButton><YdButton v-else as="a" variant="outline" href="/platform/plugins/minecraft-server"><YdIcon name="i-ri:arrow-left-line" />正在运营的服务器</YdButton></div>
    </section>
    <div class="mc-server-grid">
      <article v-for="server in model.servers" :key="server.id" class="mc-server-card" :class="{ 'is-closed': !server.enabled }">
        <header><div><h3>{{ server.name }}</h3><span>{{ server.currentSeason?.name || '暂无周目' }}</span></div><div class="mc-card-status"><span v-if="!server.enabled" class="mc-closed-badge">已关闭</span><YdTag :variant="server.status?.status === 'ONLINE' ? 'default' : 'secondary'">{{ model.statusText(server.status?.status) }}</YdTag></div></header>
        <div class="mc-online"><strong>{{ server.status?.onlinePlayers ?? 0 }}</strong><span v-if="(server.status?.maxPlayers ?? 0) > 0">/ {{ server.status?.maxPlayers }} 在线</span><span v-else>人在线</span></div>
        <div class="mc-line-list"><div v-for="endpoint in server.endpoints" :key="endpoint.id || endpoint.host" class="mc-line"><span>{{ endpoint.name }}</span><code>{{ model.endpointAddress(endpoint) }}</code></div></div>
        <div class="mc-card-actions"><YdButton as="a" :href="`/platform/plugins/minecraft-server/detail?id=${encodeURIComponent(server.id)}${model.closedSurface ? '&closed=true' : ''}`"><YdIcon name="i-ri:file-info-line" />详情</YdButton></div>
      </article>
      <YdCard v-if="!model.servers.length" title="暂无服务器" description="Empty"><div class="mc-empty">{{ model.closedSurface ? '暂无已关闭服务器。' : '管理员还没有发布服务器。' }}</div></YdCard>
    </div>
    <YdPagination v-model:page="model.serverPager.page" v-model:size="model.serverPager.size" :total="model.serverPager.total" class="mt-3" @page-change="reload" @size-change="reload" />
  </section>
</YdPageMain>
</template>
<script setup lang="ts">
// yudream-self-chrome 页面自管页头与卡片
import { YdButton, YdCard, YdIcon, YdPageHeader, YdPageMain, YdPagination, YdTag } from '@yudream/components'
import type { MinecraftServerPluginModel } from '../composables/useMinecraftServerPlugin'
const props = defineProps<{ model: MinecraftServerPluginModel }>()
async function reload() { await props.model.load(false, props.model.closedSurface) }
</script>
