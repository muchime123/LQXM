import { ref, onMounted, onUnmounted } from 'vue'

export function useTitleMenu() {
  const titleMenuOpen = ref(false)
  const titleMenuStyle = ref<Record<string, string>>({})

  function handleClickOutside(event: MouseEvent) {
    const target = event.target as Element | null
    if (
      target &&
      !target.closest('.action-menu') &&
      !target.closest('.action-menu-popover') &&
      !target.closest('.row-more-menu') &&
      !target.closest('.asset-more-menu')
    ) {
      titleMenuOpen.value = false
    }
  }

  function closeTitleMenu() {
    titleMenuOpen.value = false
  }

  function toggleTitleMenu(event: MouseEvent) {
    if (titleMenuOpen.value) {
      closeTitleMenu()
      return
    }
    const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
    const menuWidth = 132
    const menuHeight = 54
    const gap = 8
    titleMenuStyle.value = {
      position: 'fixed',
      top: `${Math.min(window.innerHeight - menuHeight - gap, Math.max(gap, rect.bottom + gap))}px`,
      left: `${Math.min(window.innerWidth - menuWidth - gap, Math.max(gap, rect.right - menuWidth))}px`
    }
    titleMenuOpen.value = true
  }

  onMounted(() => {
    document.addEventListener('click', handleClickOutside)
  })

  onUnmounted(() => {
    document.removeEventListener('click', handleClickOutside)
  })

  return {
    titleMenuOpen,
    titleMenuStyle,
    closeTitleMenu,
    toggleTitleMenu
  }
}
