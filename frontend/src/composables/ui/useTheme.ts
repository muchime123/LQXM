import { ref, onMounted } from 'vue'

export function useTheme() {
  const isDark = ref(false)

  function toggleTheme() {
    isDark.value = !isDark.value
    document.documentElement.setAttribute('data-theme', isDark.value ? 'dark' : 'light')
    localStorage.setItem('theme', isDark.value ? 'dark' : 'light')
  }

  function initTheme() {
    const savedTheme = localStorage.getItem('theme')
    if (savedTheme === 'dark' || (!savedTheme && window.matchMedia('(prefers-color-scheme: dark)').matches)) {
      isDark.value = true
      document.documentElement.setAttribute('data-theme', 'dark')
    }
  }

  onMounted(initTheme)

  return { isDark, toggleTheme }
}
