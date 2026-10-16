/**
 * 产品配置（命名规则）类型。
 *
 * ⚠️ 2026-10-06：原先还带三个客户端函数（`configurationDirectory` / `configurationRule` /
 * `previewConfiguration`）与 `DirectoryProduct` 类型 —— 它们只服务已下线的「配置产品审核」弹窗。
 * `ProductConfigurationRuleDialog.vue` 走 `http` 直调 `/product-configurations/*`，不经这里，
 * 故一并删除。留底于 commit 23a0eda。
 */
export interface ConfigOption { optionId: number; groupCode: string; technical: boolean; exclusiveGroup: string }
export interface ConfigDefinition { productType: string; requiredFields: string[]; options: ConfigOption[]; includeElectrical: boolean }
export interface ConfigRule { id: number; baseProductId: number; version: number; revision: number; status: string; definition: ConfigDefinition; labels: { productName: string; productCode: string; optionNames: Record<string, string> } }
export interface NamePreview { namePreview: string; specificationPreview: string; completionState: string; fieldErrors: Record<string, string>; confirmationItems: string[] }
