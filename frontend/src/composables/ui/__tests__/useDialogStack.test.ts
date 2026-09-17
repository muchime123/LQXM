import { describe, it, expect, beforeEach } from 'vitest'
import { ref } from 'vue'
import { useDialogStack } from '../useDialogStack'

describe('useDialogStack', () => {
  let stack: ReturnType<typeof useDialogStack>

  beforeEach(() => {
    stack = useDialogStack()
  })

  describe('register / unregister', () => {
    it('registers a modal with ref visible', () => {
      const visible = ref(false)
      const close = () => {
        visible.value = false
      }

      stack.register('test', visible, close)

      expect(stack.isOpen('test')).toBe(false)
      visible.value = true
      expect(stack.isOpen('test')).toBe(true)
    })

    it('registers a modal with function getter', () => {
      let flag = false
      const close = () => {
        flag = false
      }

      stack.register('fn', () => flag, close)

      expect(stack.isOpen('fn')).toBe(false)
      flag = true
      expect(stack.isOpen('fn')).toBe(true)
    })

    it('registers a modal with boolean literal', () => {
      const close = () => {}
      stack.register('bool', true, close)
      expect(stack.isOpen('bool')).toBe(true)
    })

    it('overrides existing registration with same name', () => {
      const v1 = ref(false)
      const v2 = ref(true)
      stack.register('dup', v1, () => {})
      stack.register('dup', v2, () => {})

      expect(stack.isOpen('dup')).toBe(true)
    })

    it('unregisters a modal', () => {
      const visible = ref(true)
      stack.register('gone', visible, () => {})
      expect(stack.isOpen('gone')).toBe(true)

      stack.unregister('gone')
      expect(stack.isOpen('gone')).toBe(false)
    })
  })

  describe('priority ordering', () => {
    it('closeTopmost closes highest priority open modal first', () => {
      const low = ref(true)
      const high = ref(true)
      const lowClosed = ref(false)
      const highClosed = ref(false)

      stack.register(
        'low',
        low,
        () => {
          low.value = false
          lowClosed.value = true
        },
        10
      )
      stack.register(
        'high',
        high,
        () => {
          high.value = false
          highClosed.value = true
        },
        100
      )

      const didClose = stack.closeTopmost()

      expect(didClose).toBe(true)
      expect(highClosed.value).toBe(true)
      expect(lowClosed.value).toBe(false)
      expect(high.value).toBe(false)
      expect(low.value).toBe(true)
    })

    it('closeTopmost skips closed modals and finds next', () => {
      const a = ref(false)
      const b = ref(true)
      const bClosed = ref(false)

      stack.register(
        'a',
        a,
        () => {
          a.value = false
        },
        100
      )
      stack.register(
        'b',
        b,
        () => {
          b.value = false
          bClosed.value = true
        },
        50
      )

      const didClose = stack.closeTopmost()

      expect(didClose).toBe(true)
      expect(bClosed.value).toBe(true)
    })

    it('closeTopmost returns false when nothing is open', () => {
      const v = ref(false)
      stack.register('closed', v, () => {}, 10)
      expect(stack.closeTopmost()).toBe(false)
    })
  })

  describe('query methods', () => {
    it('anyOpen returns true when at least one modal is open', () => {
      const a = ref(false)
      const b = ref(false)
      stack.register('a', a, () => {})
      stack.register('b', b, () => {})

      expect(stack.anyOpen.value).toBe(false)
      a.value = true
      expect(stack.anyOpen.value).toBe(true)
    })

    it('anyOpenExcept excludes specified names', () => {
      const detail = ref(true)
      const editor = ref(true)
      stack.register('detail', detail, () => {}, 10)
      stack.register('editor', editor, () => {}, 100)

      expect(stack.anyOpenExcept('detail')).toBe(true)
      expect(stack.anyOpenExcept('editor')).toBe(true)
      expect(stack.anyOpenExcept('detail', 'editor')).toBe(false)
    })

    it('openList returns names sorted by priority desc', () => {
      const a = ref(true)
      const b = ref(true)
      const c = ref(false)
      stack.register('a', a, () => {}, 30)
      stack.register('b', b, () => {}, 50)
      stack.register('c', c, () => {}, 10)

      expect(stack.openList.value).toEqual(['b', 'a'])
    })

    it('isOpen returns false for unregistered name', () => {
      expect(stack.isOpen('missing')).toBe(false)
    })
  })

  describe('closeAll', () => {
    it('closes all open modals', () => {
      const a = ref(true)
      const b = ref(true)
      const c = ref(false)
      stack.register('a', a, () => {
        a.value = false
      })
      stack.register('b', b, () => {
        b.value = false
      })
      stack.register('c', c, () => {
        c.value = false
      })

      stack.closeAll()

      expect(a.value).toBe(false)
      expect(b.value).toBe(false)
      expect(c.value).toBe(false)
    })
  })

  describe('legacy aliases', () => {
    it('registerModal is alias for register', () => {
      const v = ref(true)
      stack.registerModal('alias', v, () => {})
      expect(stack.isOpen('alias')).toBe(true)
    })

    it('anyModalOpen is alias for anyOpen', () => {
      const v = ref(true)
      stack.register('x', v, () => {})
      expect(stack.anyModalOpen.value).toBe(true)
    })

    it('openModals is alias for openList', () => {
      const v = ref(true)
      stack.register('y', v, () => {}, 5)
      expect(stack.openModals.value).toEqual(['y'])
    })
  })
})
