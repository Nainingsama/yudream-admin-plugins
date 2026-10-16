<script setup lang="ts">
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { computed, watch } from 'vue'
import { useMinecraftServerPlugin } from './composables/useMinecraftServerPlugin'
import { needsServerFormPrefill, resolveRouteTarget, serverPageKind } from './utils/routeTarget'
import AdminPage from './pages/AdminPage.vue'
import DetailPage from './pages/DetailPage.vue'
import ListPage from './pages/ListPage.vue'
import OperationsPage from './pages/OperationsPage.vue'
import PlayersAdminPage from './pages/PlayersAdminPage.vue'
import SeasonsPage from './pages/SeasonsPage.vue'
import ServerEditorPage from './pages/ServerEditorPage.vue'

const props = defineProps<{ sdk: YuDreamPluginSdk, route?: RouteLocationNormalizedLoaded }>()
const model = useMinecraftServerPlugin(props.sdk)
const componentName = computed(() => (props.route?.meta?.plugin as { component?: string } | undefined)?.component)
const page = computed(() => {
  if (componentName.value === 'minecraft-server/Admin') return AdminPage
  if (componentName.value === 'minecraft-server/Editor') return ServerEditorPage
  if (componentName.value === 'minecraft-server/Seasons') return SeasonsPage
  if (componentName.value === 'minecraft-server/Operations') return OperationsPage
  if (componentName.value === 'minecraft-server/Players') return PlayersAdminPage
  if (componentName.value === 'minecraft-server/Detail') return DetailPage
  return ListPage
})
const isAdminPage = computed(() => [AdminPage, ServerEditorPage, SeasonsPage, OperationsPage, PlayersAdminPage].includes(page.value))
const isClosedPage = computed(() => componentName.value === 'minecraft-server/Closed' || props.route?.query?.closed === 'true')

async function loadCurrentPage() {
  const id = String(props.route?.query?.id || '')
  if (id) model.selectedId = id
  await model.load(isAdminPage.value, !isAdminPage.value && isClosedPage.value)
  const kind = serverPageKind(componentName.value)
  if (id) {
    // 目标服务器可能不在当前页（列表只加载一页）：一律按 id 直接拉详情并把详情并回列表，
    // 否则第二页起的「编辑」会落到空表单、周目/操作记录/玩家统计会挂错服务器。
    try {
      await model.selectServer(id)
      const target = resolveRouteTarget(model.servers, id)
      if (target && needsServerFormPrefill(kind)) {
        // 编辑器与周目页都读 serverForm：按目标服务器预填
        model.editServer(target)
      }
    } catch {
      // 服务器不存在/无权限：保持列表态，由页面自身呈现空态
    }
    return
  }
  if (kind === 'editor') {
    model.newServer()
  }
}

watch([page, () => props.route?.query?.id, isClosedPage], loadCurrentPage, { immediate: true })
</script>

<template><div class="mc-plugin"><component :is="page" :model="model" /></div></template>
