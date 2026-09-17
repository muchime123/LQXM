import { describe, expect, it } from 'vitest'
import { FILE_ASSET_MESSAGES, fileAssetDeleteSuccess, fileCapabilityUnsupportedMessages } from '../fileAssetMessages'

describe('file asset messages', () => {
  it('keeps destructive operation feedback explicit', () => {
    expect(fileAssetDeleteSuccess(false)).toBe('文件已移入回收站')
    expect(fileAssetDeleteSuccess(true)).toBe('文件已永久删除')
    expect(fileAssetDeleteSuccess(false, true)).toBe('文件已批量移入回收站')
    expect(fileAssetDeleteSuccess(true, true)).toBe('文件已批量永久删除')
  })

  it('uses consistent folder and default-file terminology', () => {
    expect(FILE_ASSET_MESSAGES.folderOpened).toContain('文件夹')
    expect(fileCapabilityUnsupportedMessages.OPEN_FOLDER).toContain('文件夹')
    expect(fileCapabilityUnsupportedMessages.SET_DEFAULT).toContain('设为默认文件')
  })
})
