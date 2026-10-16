import type { MinecraftServer } from '../types'

/** 插件内页面种类（由路由 meta.plugin.component 推导）。 */
export type ServerPageKind = 'editor' | 'seasons' | 'operations' | 'players' | 'detail' | 'list'

/**
 * 路由组件名 → 页面种类。
 * 管理页（编辑器/周目/操作记录/玩家统计）与公开列表/详情都要按路由里的 id 定位服务器。
 */
export function serverPageKind(component: string | undefined): ServerPageKind {
  if (component === 'minecraft-server/Editor') return 'editor'
  if (component === 'minecraft-server/Seasons') return 'seasons'
  if (component === 'minecraft-server/Operations') return 'operations'
  if (component === 'minecraft-server/Players') return 'players'
  if (component === 'minecraft-server/Detail') return 'detail'
  return 'list'
}

/**
 * 该页面是否读 serverForm：编辑器与周目页都从表单渲染，进入时需按目标服务器预填；
 * 操作记录/玩家统计/详情只读列表与侧数据，不需要（也不会因此覆盖表单）。
 */
export function needsServerFormPrefill(kind: ServerPageKind): boolean {
  return kind === 'editor' || kind === 'seasons'
}

/**
 * 路由带 id 时的目标服务器解析规则：**必须按 id 直接拉详情**，不能用当前分页列表里
 * find(id) 的结果判定目标——列表只加载一页，第二页起的服务器不在其中，直接 find 会
 * 落空：编辑器退化成「新建」空表单、周目/操作记录/玩家统计挂到列表首行。
 * 抽取为纯函数，供入口页与回归测试共用。
 */
export function resolveRouteTarget(servers: MinecraftServer[], id: string): MinecraftServer | undefined {
  if (!id) {
    return undefined
  }
  return servers.find(server => server.id === id)
}
