import type { MinecraftServer } from '../types'
import { describe, expect, it } from 'vitest'
import { needsServerFormPrefill, resolveRouteTarget, serverPageKind } from './routeTarget'

function server(id: string): MinecraftServer {
  return {
    id,
    name: `服务器 ${id}`,
    descriptionMarkdown: '',
    enabled: true,
    sort: 0,
    endpoints: [],
    seasons: [],
    createdAt: 0,
    updatedAt: 0,
  } as MinecraftServer
}

describe('路由目标解析', () => {
  it('把路由组件名映射为页面种类', () => {
    expect(serverPageKind('minecraft-server/Editor')).toBe('editor')
    expect(serverPageKind('minecraft-server/Seasons')).toBe('seasons')
    expect(serverPageKind('minecraft-server/Operations')).toBe('operations')
    expect(serverPageKind('minecraft-server/Players')).toBe('players')
    expect(serverPageKind('minecraft-server/Detail')).toBe('detail')
    expect(serverPageKind(undefined)).toBe('list')
  })

  it('只有渲染 serverForm 的页面需要预填表单', () => {
    // 编辑器与周目页都从 serverForm 渲染，进入时必须按目标服务器预填
    expect(needsServerFormPrefill('editor')).toBe(true)
    expect(needsServerFormPrefill('seasons')).toBe(true)
    // 操作记录/玩家统计/详情读列表与侧数据，不预填
    expect(needsServerFormPrefill('operations')).toBe(false)
    expect(needsServerFormPrefill('players')).toBe(false)
    expect(needsServerFormPrefill('detail')).toBe(false)
    expect(needsServerFormPrefill('list')).toBe(false)
  })

  it('目标服务器只在详情并回列表后才解析得到（第二页场景）', () => {
    // 列表当前只有第 1 页：目标在第二页，直接 find 必然落空——这正是历史上
    // 「第二页编辑变成新建/周目挂错服务器」的根因
    const page = [server('s1'), server('s2')]
    expect(resolveRouteTarget(page, 's7')).toBeUndefined()
    // 入口按 id 拉详情后会把目标并回列表，此时才能解析
    page.push(server('s7'))
    expect(resolveRouteTarget(page, 's7')?.id).toBe('s7')
    // 无 id（新建）不解析目标
    expect(resolveRouteTarget(page, '')).toBeUndefined()
  })
})
