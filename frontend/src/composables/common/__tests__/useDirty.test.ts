import { beforeEach, describe, expect, it } from 'vitest'
import {
  clearAllDirty,
  clearDirty,
  getDirtyKeys,
  getDirtyState,
  isDirty,
  setDirty
} from '../useDirty'

describe('useDirty', () => {
  beforeEach(() => {
    clearAllDirty()
  })

  describe('setDirty / getDirtyState', () => {
    it('标记单个 key 后可读取', () => {
      setDirty('form-a')
      expect(getDirtyState('form-a')).toBe(true)
    })

    it('未标记的 key 返回 false', () => {
      expect(getDirtyState('form-b')).toBe(false)
    })
  })

  describe('isDirty', () => {
    it('无标记时为 false', () => {
      expect(isDirty()).toBe(false)
    })

    it('有标记时为 true', () => {
      setDirty('form-a')
      expect(isDirty()).toBe(true)
    })

    it('清空后回到 false', () => {
      setDirty('form-a')
      clearAllDirty()
      expect(isDirty()).toBe(false)
    })
  })

  describe('getDirtyKeys', () => {
    it('返回全部已标记的 key', () => {
      setDirty('form-a')
      setDirty('form-b')
      expect(getDirtyKeys().sort()).toEqual(['form-a', 'form-b'])
    })

    it('清空后返回空数组', () => {
      setDirty('form-a')
      clearAllDirty()
      expect(getDirtyKeys()).toEqual([])
    })
  })

  describe('clearDirty', () => {
    it('删除指定 key', () => {
      setDirty('form-a')
      setDirty('form-b')
      clearDirty('form-a')
      expect(getDirtyState('form-a')).toBe(false)
      expect(getDirtyState('form-b')).toBe(true)
    })

    it('空字符串守卫：不删除任何 key', () => {
      setDirty('form-a')
      clearDirty('')
      expect(getDirtyState('form-a')).toBe(true)
    })

    it('删除不存在的 key 不报错', () => {
      expect(() => clearDirty('not-exist')).not.toThrow()
    })
  })

  describe('clearAllDirty', () => {
    it('清空多个标记', () => {
      setDirty('form-a')
      setDirty('form-b')
      setDirty('form-c')
      clearAllDirty()
      expect(getDirtyKeys()).toEqual([])
      expect(isDirty()).toBe(false)
    })
  })

  describe('跨用例隔离', () => {
    it('用例 1：标记 form-a', () => {
      setDirty('form-a')
      expect(isDirty()).toBe(true)
    })

    it('用例 2：不应继承上一用例的状态', () => {
      expect(isDirty()).toBe(false)
    })
  })
})
