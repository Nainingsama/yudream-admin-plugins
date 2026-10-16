<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { MinecraftServer } from '../types'
import type { MinecraftServerPluginModel } from '../composables/useMinecraftServerPlugin'
import { useRouter } from 'vue-router'
import { YdButton, YdCard, YdIcon, YdPageHeader, YdPageMain, YdPagination, YdResponsiveTable, YdSearchBar, YdTag, YdTooltip, useYdModal } from '@yudream/components'

const props = defineProps<{ model: MinecraftServerPluginModel }>()
const router = useRouter()
const modal = useYdModal()
const columns: TableColumn<MinecraftServer>[] = [
  { accessorKey: 'name', header: '服务器', width: 240, fixed: 'left' },
  { id: 'status', header: '状态', width: 100, align: 'center' },
  { id: 'enabled', header: '发布', width: 100, align: 'center' },
  { accessorKey: 'sort', header: '排序', width: 80, align: 'center' },
  { id: 'operation', header: '操作', width: 320, align: 'center', fixed: 'right' },
]
function go(component: 'Editor' | 'Seasons' | 'Operations' | 'Players', id?: string) {
  return router.push({ path: `/platform/plugins/minecraft-server/admin/${component.toLowerCase()}`, query: id ? { id } : {} })
}
async function reload() { await props.model.load(true) }
/** 时间字段（epoch 毫秒 / ISO 串 / Date）转毫秒，无法识别返回 null。 */
function toMillis(value: unknown): number | null {
  if (value instanceof Date) {
    return value.getTime()
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value
  }
  if (typeof value === 'string' && value) {
    const parsed = Date.parse(value)
    return Number.isNaN(parsed) ? null : parsed
  }
  return null
}
/** 状态列诊断：悬停展示快照检测时间与每条线路的探测结果/失败原因（离线时区分网络不通、地址失效或快照冻结）。 */
function statusTip(server: MinecraftServer): string {
  const status = server.status
  if (!status) {
    return '尚无状态快照：面板从未探测成功，请检查线路地址是否可达'
  }
  const parts = [`上次检测 ${props.model.formatTime(status.checkedAt) || '未知'}`]
  if (status.source === 'panel') {
    // 同机部署时面板经公网地址连不上自己的游戏端口：按面板容器状态判在线，说明依据
    parts.push('按面板容器状态判为在线（绑定的实例在运行中；面板自身网络探不到该端口，线路失败原因见下）')
  }
  // 快照明显过期＝刷新停摆（探测挂起/调度被拖住），明确喊出来而不是只显示一个旧时间
  const checkedAtMs = toMillis(status.checkedAt)
  if (checkedAtMs !== null) {
    const staleMinutes = Math.floor((Date.now() - checkedAtMs) / 60_000)
    if (staleMinutes >= 5) {
      parts.push(`快照已过期 ${staleMinutes} 分钟：状态刷新可能停摆，请查看面板日志中 [minecraft-server] 开头的行`)
    }
  }
  const items = status.endpoints ?? []
  if (!items.length) {
    parts.push('未配置线路地址：没有任何线路时按离线展示')
    return parts.join(' · ')
  }
  for (const item of items) {
    const endpoint = server.endpoints.find(target => target.id === item.endpointId || target.name === item.endpointId)
    // 端口留空（0/null）＝按 SRV 或 Java 默认端口探测，不能显示成 ":0"
    const port = endpoint?.port === null || endpoint?.port === undefined || endpoint?.port === '' || endpoint?.port === 0
      ? ''
      : `:${endpoint.port}`
    const address = endpoint ? `${endpoint.host}${port}` : item.endpointId
    const detail = item.status === 'ONLINE'
      ? `在线 ${item.onlinePlayers ?? 0}/${item.maxPlayers ?? 0}`
      : (item.errorMessage || '探测失败')
    parts.push(`${address} ${detail}`)
  }
  return parts.join(' · ')
}
function confirmToggleServer(server: MinecraftServer) {
  const ending = server.enabled
  modal.confirm({
    title: ending ? '结束服务器' : '恢复服务器',
    content: ending
      ? `确认结束“${server.name}”吗？结束后将从 QQ 服务器列表移除，并归档到网站的已关闭服务器列表。`
      : `确认恢复“${server.name}”运营吗？`,
    onConfirm: () => props.model.toggleServerEnabled(server),
  })
}
</script>

<template>
  <YdPageHeader title="服务器管理" class="mb-0">
    <YdButton @click="go('Editor')"><YdIcon name="i-ri:add-line" />新增服务器</YdButton>
  </YdPageHeader>
  <YdPageMain>
    <YdResponsiveTable row-key="id" table-root-class="max-w-full overflow-x-auto rounded-lg" table-class="min-w-[980px]" border stripe column-visibility :columns="columns" :data="model.servers">
      <template #toolbar><YdSearchBar class="w-full"><div class="flex justify-end gap-2"><YdButton variant="outline" :loading="model.loading" @click="reload"><YdIcon name="i-ri:refresh-line" />刷新</YdButton></div></YdSearchBar></template>
      <template #cell-name="{ row }"><div class="grid gap-1"><strong>{{ row.original.name }}</strong><span class="font-mono text-xs text-muted-foreground">ID: {{ row.original.id }}</span></div></template>
      <template #cell-status="{ row }"><YdTooltip :text="statusTip(row.original)"><YdTag :variant="row.original.status?.status === 'ONLINE' ? 'default' : 'secondary'">{{ model.statusText(row.original.status?.status) }}</YdTag></YdTooltip></template>
      <template #cell-enabled="{ row }">{{ row.original.enabled ? '启用' : '停用' }}</template>
      <template #cell-operation="{ row }"><div class="flex flex-wrap justify-center gap-2"><YdButton size="sm" :variant="row.original.enabled ? 'destructive' : 'outline'" :loading="model.saving" @click="confirmToggleServer(row.original)">{{ row.original.enabled ? '结束服务器' : '恢复运营' }}</YdButton><YdButton size="sm" variant="outline" @click="go('Editor', row.original.id)">编辑</YdButton><YdButton size="sm" variant="outline" @click="go('Seasons', row.original.id)">周目</YdButton><YdButton size="sm" variant="outline" @click="go('Operations', row.original.id)">操作记录</YdButton><YdButton size="sm" variant="outline" @click="go('Players', row.original.id)">玩家统计</YdButton></div></template>
      <template #card="{ row }">
        <YdCard class="w-full">
          <div class="flex flex-col gap-3">
            <div class="flex items-center justify-between gap-2">
              <span class="min-w-0 break-words text-base font-semibold">{{ row.name }}</span>
              <div class="flex gap-1">
                <YdTag :variant="row.status?.status === 'ONLINE' ? 'default' : 'secondary'">{{ model.statusText(row.status?.status) }}</YdTag>
                <YdTag :variant="row.enabled ? 'default' : 'secondary'">{{ row.enabled ? '启用' : '停用' }}</YdTag>
              </div>
            </div>
            <div class="flex flex-col gap-1 text-sm">
              <div class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">ID</span>
                <span class="break-all">{{ row.id }}</span>
              </div>
              <div class="flex gap-2">
                <span class="shrink-0 text-secondary-foreground/60">排序</span>
                <span class="break-all">{{ row.sort }}</span>
              </div>
            </div>
            <div class="flex flex-wrap gap-2 border-t pt-3">
              <YdButton size="sm" :variant="row.enabled ? 'destructive' : 'outline'" :loading="model.saving" @click="confirmToggleServer(row)">{{ row.enabled ? '结束服务器' : '恢复运营' }}</YdButton>
              <YdButton size="sm" variant="outline" @click="go('Editor', row.id)">编辑</YdButton>
              <YdButton size="sm" variant="outline" @click="go('Seasons', row.id)">周目</YdButton>
              <YdButton size="sm" variant="outline" @click="go('Operations', row.id)">操作记录</YdButton>
              <YdButton size="sm" variant="outline" @click="go('Players', row.id)">玩家统计</YdButton>
            </div>
          </div>
        </YdCard>
      </template>
    </YdResponsiveTable>
    <YdPagination v-model:page="model.serverPager.page" v-model:size="model.serverPager.size" :total="model.serverPager.total" class="mt-3" @page-change="reload" @size-change="reload" />
  </YdPageMain>
</template>
