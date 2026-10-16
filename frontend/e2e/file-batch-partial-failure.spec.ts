import { expect, test } from '@playwright/test'

test('refreshes partial changes and reports completed count when a batch delete fails', async ({ page }) => {
  const deletedAssetIds: number[] = []
  await page.route('**/api/file-assets/capabilities', route =>
    route.fulfill({ json: { company: ['DELETE'] } })
  )
  await page.route('**/api/file-assets/company/assets/*', async route => {
    const match = route.request().url().match(/\/assets\/(\d+)/)
    const assetId = Number(match?.[1])
    deletedAssetIds.push(assetId)
    if (assetId === 12) {
      await route.fulfill({
        status: 500,
        json: {
          code: 500,
          message: '第二个文件删除失败',
          data: null,
          timestamp: new Date().toISOString()
        }
      })
      return
    }
    await route.fulfill({ status: 204 })
  })

  await page.goto('/')
  const result = await page.evaluate(async () => {
    const module = await (0, eval)("import('/src/composables/upload/useFileAssetActions.ts')")
    const notifications: Array<{ message: string; type?: string }> = []
    let changedCount = 0
    // notify 必须满足 NotifyFn 契约：实现里失败路径走 notify.errorWithDetail，
    // 只给一个裸函数会让它抛 TypeError 并顶掉真正的批量失败错误。
    const push = (message: string, type?: string) => notifications.push({ message, type })
    const detailOf = (err: unknown) => (err instanceof Error ? err.message : String(err ?? ''))
    const notify = Object.assign(push, {
      success: (message: string) => push(message, 'success'),
      error: (message: string) => push(message, 'error'),
      warning: (message: string) => push(message, 'warning'),
      errorWithDetail: (prefix: string, err: unknown) => {
        const detail = detailOf(err)
        push(detail ? `${prefix}：${detail}` : prefix, 'error')
      }
    })
    const actions = module.useFileAssetActions({
      notify,
      onChanged: () => { changedCount += 1 }
    })
    let thrown = ''
    try {
      await actions.batchRemove('company', [11, 12, 13])
    } catch (error) {
      thrown = error instanceof Error ? error.message : String(error)
    }
    return { notifications, changedCount, thrown }
  })

  expect(deletedAssetIds).toEqual([11, 12])
  expect(result.changedCount).toBe(1)
  expect(result.thrown).toContain('已完成 1 项')
  expect(result.notifications).toEqual([
    expect.objectContaining({
      message: expect.stringContaining('已完成 1 项'),
      type: 'error'
    })
  ])
})
