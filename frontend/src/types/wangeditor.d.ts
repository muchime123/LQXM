/**
 * @wangeditor/editor-for-vue 的 package.json "exports" 未暴露 types，
 * 且 latest 标签指向 Vue2 版本，本项目固定使用 5.x（Vue3）。
 * 这里补充模块声明，组件 props 采用宽松类型，由 Vue 模板在运行时校验。
 */
declare module '@wangeditor/editor-for-vue' {
  import type { DefineComponent } from 'vue'

  export const Editor: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export const Toolbar: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
}
