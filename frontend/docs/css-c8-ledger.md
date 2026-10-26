# C8 明细台账（无引用与归属错位样式）

> 本文件由 `scripts/audit/c8-ledger-md.mjs` 机器生成，数据来源：`scripts/audit/c8-ledger.mjs`（静态分类）与 `_audit_shots/c8-runtime-*.json`（运行时验证）。
> 口径说明：分类依据是「类名是否出现在组件模板或脚本文本中」，属于**静态关联**，不等于实际渲染结果；因此每条都补了运行时命中情况。

## src/styles/media-account-view.css

规则总数 270：A 全仓无引用 79 ／ B 归属错位 12 ／ C 单组件使用 130 ／ D 多组件共用 49

### A 类：全仓无引用（删除候选）

| 行号 | 选择器 | 实际消费者 | 样式来源 | 风险 | 建议 | 验证方式 |
|---|---|---|---|---|---|---|
| 34 | `.platform-stat-icon` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 45 | `.platform-stat-info` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 50 | `.platform-stat-name` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 56 | `.platform-stat-value` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 62 | `.platform-stat-sub` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 68 | `.platform-stat-total` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 73 | `.platform-stat-total .platform-stat-name, .platform-stat-total .platfo` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 77 | `.platform-stat-total .platform-stat-value` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 185 | `.stale-inline` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 304 | `.media-filter-select` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 385 | `.table-header-left` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 403 | `.table-search` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 407 | `.dialog-eyebrow` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 437 | `.form-dialog-footer` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 451 | `.editor-topline` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 461 | `.editor-topline strong` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 479 | `.editor-section-title` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 525 | `.editor-dialog-footer` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 532 | `.form-section-title` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 554 | `.form-stack` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 661 | `.date-input-wrap` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 665 | `.date-icon` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 673 | `.supplement-card` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 682 | `.dialog-actions` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 707 | `.btn-cancel` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 720 | `.btn-cancel:hover` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 735 | `.media-form-group` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 739 | `.media-form-label` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 747 | `.media-form-tip` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 753 | `.media-form-actions` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 805 | `.update-type-radio` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 811 | `.update-type-option` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 820 | `.update-type-option:hover` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 829 | `.update-type-option .update-type-icon` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 834 | `.update-type-option .update-type-label` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1087 | `.import-count-text` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1092 | `.import-count-text strong` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1101 | `.btn-text` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1110 | `.btn-text:hover:not(:disabled)` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1114 | `.btn-text:disabled` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1257 | `.platform-config-list` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1262 | `.platform-config-item` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1271 | `.platform-config-item:last-child` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1275 | `.platform-config-color` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1286 | `.platform-config-info` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1290 | `.platform-config-name` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1296 | `.platform-config-icon` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1301 | `.platform-config-add` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1307 | `.platform-config-add-input` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1313 | `.drawer-overlay, .analytics-drawer-overlay` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1328 | `.drawer-overlay` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1365 | `.drawer-header, .analytics-drawer-header` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1373 | `.drawer-header h3` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1380 | `.analytics-drawer-title` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1387 | `.drawer-content, .analytics-drawer-body` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1394 | `.drawer-resizer, .analytics-drawer-resizer` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1404 | `.drawer-resizer` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1411 | `.drawer-resizer svg` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1422 | `.close-btn` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1435 | `.close-btn:hover` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1440 | `.close-btn svg` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1679 | `.analytics-filters` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1686 | `.analytics-filter-group` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1692 | `.analytics-filter-label` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1745 | `.heatmap-container` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1749 | `.heatmap-header` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1756 | `.heatmap-title` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1762 | `.heatmap-legend` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1770 | `.heatmap-legend-color` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1776 | `.heatmap-grid` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1782 | `.heatmap-row` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1787 | `.heatmap-cell` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1795 | `.heatmap-cell:hover` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1799 | `.heatmap-cell-0` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1803 | `.heatmap-cell-1` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1807 | `.heatmap-cell-2` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1811 | `.heatmap-cell-3` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1815 | `.heatmap-cell-4` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1819 | `.heatmap-cell-5` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |

### B 类：归属错位（不等于死代码，需逐个核实）

| 行号 | 选择器 | 实际消费者（是否自带同选择器） | 运行时元素 | 本文件副本是否生效 | 风险 | 建议 | 验证方式 |
|---|---|---|---|---|---|---|---|
| 342 | `.row-actions` | DeviceVideoLibraryView（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 469 | `.editor-section` | AssetTagEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 474 | `.editor-section:first-of-type` | AssetTagEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 548 | `.optional-tag, .field-hint` | CashflowEntitiesView（自带）、TrackingProductBenchmarkPanel（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 772 | `.batch-summary-box` | AssignOwnerDialog（自带）、BatchPublishTaskDialog（自带）、BatchUpdateRecordDialog（自带） | 2 | 否 | 低 | 可判无效；声明与消费者自带版本**不一致** | 静态引用 + 运行时匹配 |
| 783 | `.batch-summary-box strong` | AssignOwnerDialog（自带）、BatchPublishTaskDialog（自带）、BatchUpdateRecordDialog（自带） | 2 | 否 | 低 | 可判无效；声明与消费者自带版本**不一致** | 静态引用 + 运行时匹配 |
| 788 | `.batch-summary-box span` | AssignOwnerDialog（自带）、BatchPublishTaskDialog（自带）、BatchUpdateRecordDialog（自带） | 2 | 否 | 低 | 可判无效；声明与消费者自带版本**不一致** | 静态引用 + 运行时匹配 |
| 792 | `.batch-check-row` | BatchPublishTaskDialog（自带）、BatchUpdateRecordDialog（自带） | 1 | 否 | 低 | 可判无效；声明与消费者自带版本一致 | 静态引用 + 运行时匹配 |
| 800 | `.batch-check-row input` | BatchPublishTaskDialog（自带）、BatchUpdateRecordDialog（自带） | 1 | 否 | 低 | 可判无效；声明与消费者自带版本一致 | 静态引用 + 运行时匹配 |
| 1073 | `.import-footer` | ExternalIdentityImportDialog（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 1339 | `.drawer, .analytics-drawer` | H3LedgerDetailDrawer（无）、H3LedgerPanel（无）、TrackingDetailDrawer（无） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 1352 | `.drawer` | H3LedgerDetailDrawer（无）、H3LedgerPanel（无）、TrackingDetailDrawer（无） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |

### C 类：仅单组件使用（迁移可行性待试点验证，暂不迁移）

共 130 条。逐条记录略，风险点汇总见正文「C 类迁移可行性」。按使用者分布：
- MediaAnalyticsPanel：35 条
- ImportUpdatePreviewModal：28 条
- MediaPlatformConfigModal：21 条
- MediaAccountView：15 条
- MediaAccountEditorModal：14 条
- MediaAccountStats：12 条
- UpdateRecordFormDialog：5 条

### D 类：多组件共用（评估保留 scoped 还是模块共享）

共 49 条。使用组件数分布：
- 被 7 个组件共用：1 条
- 被 6 个组件共用：2 条
- 被 5 个组件共用：2 条
- 被 4 个组件共用：14 条
- 被 3 个组件共用：16 条
- 被 2 个组件共用：14 条

## src/styles/packaging-detail-drawer.css

规则总数 314：A 全仓无引用 44 ／ B 归属错位 53 ／ C 单组件使用 156 ／ D 多组件共用 61

### A 类：全仓无引用（删除候选）

| 行号 | 选择器 | 实际消费者 | 样式来源 | 风险 | 建议 | 验证方式 |
|---|---|---|---|---|---|---|
| 39 | `.folder-quality-btn` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 50 | `.folder-quality-btn:hover` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 55 | `.folder-quality-btn span` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 63 | `.action-divider` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 200 | `.zone-heading` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 576 | `.variant-image-manager` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 584 | `.manager-header` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 594 | `.manager-header button` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 602 | `.manager-header button:hover` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 606 | `.manager-grid` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 612 | `.manager-item` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 626 | `.manager-item img` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 632 | `.manager-item em` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 644 | `.manager-actions` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 658 | `.manager-item:hover .manager-actions` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 662 | `.manager-actions button` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 674 | `.manager-actions button:hover` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 687 | `.slide-up-enter-active, .slide-up-leave-active` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 692 | `.slide-up-enter-from, .slide-up-leave-to` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 704 | `.zone-heading p` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 710 | `.quality-filter-bar` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 717 | `.quality-filter-bar button` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 740 | `.quality-filter-bar span` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1133 | `.film-note-panel` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1138 | `.film-note-panel div` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1146 | `.film-note-panel span` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1153 | `.film-note-panel strong, .film-note-panel p` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1161 | `.film-rule-list` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1168 | `.film-rule-list span` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1350 | `.spec-card-tag-row` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 1359 | `.spec-card-tag-row span` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 2007 | `.icon-button` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 2192 | `.exchange-rate-row` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 2202 | `.exchange-rate-row span:first-child` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 2208 | `.exchange-rate-input-group` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 2214 | `.exchange-rate-input-group input` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 2224 | `.exchange-rate-label` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 2278 | `.tag-editor-modal` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 2287 | `.tag-editor-modal header` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 2293 | `.tag-editor-modal h3` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 2322 | `.tag-editor-modal footer` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 2436 | `.batch-progress-backdrop` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 2440 | `.batch-progress-modal` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |
| 2452 | `.batch-progress-modal h4` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |

### C8-A 第一批实施记录（2026-10-23，packaging-detail-drawer.css）

**删除单位核查（先于删除）**：台账「A 类 44 条」按台账口径是 **44 条完整规则**（含全部选择器分支），全部为顶层规则（无 @media 嵌套、无共享声明块混入）。其中 41 条单选择器 + 1 条双分支（`.film-note-panel strong, .film-note-panel p`，两分支同属死前缀 `.film-note-panel`）→ **实际删除 42 条完整规则（43 个选择器分支）**；排除 2 条：

- `.slide-up-enter-active, .slide-up-leave-active` 与 `.slide-up-enter-from, .slide-up-leave-to`：Vue transition 动态生成类。全仓无 `slide-up` 字符串、无 `<transition name="slide-up">`，静态上可删，但按「动态依赖先排除」原则本批保留（约 11 行），留待单独确认。

**语义边界核查**：`batch-progress-*` / `tag-editor-*` / `exchange-rate-*` 存在同名前缀的**活跃兄弟类**（`batch-progress-bar/fill/meta/status`、`tag-editor-field`、`exchange-rate-inline`），本次只删精确类名，兄弟类全部未动；`.zone-heading` 与 `.spec-detail-card header` 的共享声明块、`.zone-heading h3, .spec-detail-card h3, .series-card h3` 组合块均保留，只删了独立 `.zone-heading` 规则。

**验证结果**（脚本存于 `frontend/_audit_shots/c8a-*.mjs`）：

- 静态：全仓 2910 个源文件按精确类名检索 0 引用（前端 src + corporate-website + backend + docs）。
- 运行时（删除前）：`/#/packaging` 列表、规格（301 变体卡）、包材概览、模具（54）、素材（71）× 双主题 × 1600/1024 共 28 状态，44 条规则及全部分支 querySelectorAll 均 0 命中，DOM 类名对 14 个候选词 0 子串匹配。
- 计算样式：删除前后同路径 28 状态、每状态 ≤1500 个带类元素 × 32 个属性，**0 差异**。
- 截图：双主题 1600px 像素级比对；用「同版本连跑两次」建立噪声基线后，删除前后确定性对照为 **0 像素差异**（dark 主题与素材管理 tab 的少量差异与噪声基线同量级，属异步图片/动画帧抖动；`light-05-tab-1` 一轮 5% 差异经 before vs before2 复现排除，与 CSS 版本无关）。
- lint 通过；vitest 70 文件 / 527 用例全通过。

**构建体积（同一 HEAD，仅该 CSS 一个文件不同，可归因）**：

| 指标 | 删除前 | 删除后 | 差值 |
|---|---|---|---|
| 源文件字节 | 50988 | 44396 | -6592 |
| 源规则数（含 @media 内规则口径） | 315 | 273 | -42 |
| PackagingView 路由产物 CSS 文件数 | 1 | 1 | 0 |
| PackagingView CSS raw | 190766 | 172034 | -18732 |
| PackagingView CSS gzip | 24843 | 22828 | -2015 |
| PackagingView CSS brotli | 13067 | 12261 | -806 |

> 该文件被 3 个组件以 scoped 方式内联（运行时承载作用域号 3 个；删除前产物中 `folder-quality-btn` 出现 9 次 = 3 规则 × 3 副本），故产物缩减 ≈ 源缩减 × 3，属真实收益。全站产物 CSS 文件数 58 不变。首轮前后构建之间发生了并行提交（13d2e86 → fcb5186），已用「同 HEAD 临时还原删除前版本重建」方式归因，PackagingView chunk 数字与 13d2e86 基线完全一致。注意：本批代码改动被并行会话的提交 fcb5186 一并带入仓库历史，未单独形成 `refactor(css)` 提交（不做历史改写）。

**遗留发现（下一批候选，本批未动）**：`.manager-item.active`、`.manager-actions button.danger`、`.manager-actions button.danger:hover`、`.quality-filter-bar button:hover, .quality-filter-bar button.active` —— 因通用词 `active`/`danger` 在其他组件出现而被扫描器归为 C/D，实际其宿主类（`.manager-item` / `.manager-actions` / `.quality-filter-bar`）已删，这 4 条也是死规则。属于扫描器对「通用词尾部 token」的盲点，建议下一批连同 B 类核查一起修正分类方法。（已于第二批处理，见下。）

### C8-A 第二批实施记录（2026-10-26，先修扫描器再删）

#### 1. 扫描器修正：`scripts/audit/c8-scan-v2.mjs`

v1 判定式是「规则里任一类名 token 在全仓任一位置出现 → 视为被引用」。它会因通用词（`.manager-item.active` 里的 `active`）把死规则判活，也无法识别复合选择器里的死分支。

v2 的判定（遵循四条原则）：

1. 按**完整选择器分支**（逗号拆分、括号感知）独立判定，不再按 token 集合。
2. 分支内类名区分**宿主类**与**状态/修饰类**（`active/danger/disabled/selected/…`，以及 `is-`/`has-`/`not-`/`el-` 前缀）；状态类一律不作为「存在消费者」的证据。
3. 伪类（`:hover`/`:focus`/`:disabled`/`:active`…）与伪元素不参与类名匹配。
4. **保守方向**：只要分支内任一宿主类被引用即判「有消费者」；只有全部宿主类都无引用才判无消费者；全是状态类同样判有消费者。状态词表收录不全只会少删，不会误删。动态依赖（伪元素、`:deep`、第三方/框架前缀、**Vue 过渡类 `-enter/-leave/-move`**、ts/js 字符串、同前缀拼接）单独列入「需运行时核实」，不直接进入删除候选。

修复过程中还发现 v1 的一处真实缺陷：`class` 绑定只匹配了 `v-bind:class`，漏掉 `:class` 简写；且属性值用 `[^"']` 捕获，对象写法里带单引号的键会被截断。修复后 `PackagingSpecDetailPanel.vue` 通过 `:class` 动态绑定的 `.detail-section-dimensions` 才被正确识别为活跃类——正是「动态依赖」这个风险点的实例。

**用 v2 复核第一批**：重扫删除前版本（13d2e86），v1 判 A 的 44 条中 **44 条仍判整条无消费者，0 条被 v2 认为有消费者**；已删的 42 条无一被 v2 判活；`slide-up` 两条被准确标记为「疑似 Vue 过渡类（运行时生成）」，与第一批保留它们的决定一致。

#### 2. 第二批删除明细（9 处 = 5 条整条 + 4 个分支）

整条删除：

| 行 | 选择器 | 判定依据 |
|---|---|---|
| 536 | `.manager-item.active` | 状态类 `active` 剔除后宿主类 `manager-item` 全仓 0 引用；运行时 0 命中 |
| 540 | `.manager-actions button.danger` | 剔除 `danger` 后宿主类 `manager-actions` 0 引用；运行时 0 命中 |
| 544 | `.manager-actions button.danger:hover` | 同上；伪类不参与匹配 |
| 567 | `.quality-filter-bar button:hover, .quality-filter-bar button.active` | 两个分支宿主类同为 `quality-filter-bar`，均 0 引用 |
| 1367 | `.detail-field` | 全仓 0 引用（已由 `LqDescriptions` 取代）；运行时 0 命中 |

仅移除无效分支（声明块仍被其他分支使用，不动整条）：

| 行 | 规则 | 移除的分支 | 保留的分支 |
|---|---|---|---|
| 162 | `.zone-heading, .spec-detail-card header` | `.zone-heading` | `.spec-detail-card header` |
| 561 | `.zone-heading h3, .spec-detail-card h3, .series-card h3` | `.zone-heading h3` | 另两条 |
| 1722 | `.btn-sm, .icon-button` | `.icon-button` | `.btn-sm` |
| 1831 | `… input:focus` 六分支组合块 | `.exchange-rate-input-group input:focus` | 其余五条 |

**未删**：`slide-up` 两条（按约定保留为独立待核项）；`.detail-section-dimensions .detail-field`（容器类 `.detail-section-dimensions` 经 `:class` 动态生效，v2 按保守规则判「有消费者」；运行时 28 状态 0 命中，留待扫描器补上「复合选择器中一宿主类活跃、另一宿主类全仓 0 引用」的判定后再处理）。

#### 3. 验证

- 静态：v2 全仓扫描（覆盖 `class=` / `:class=` 对象键 / 三元字符串 / `classList` / render 函数 `class:`），宿主类 0 引用。
- 运行时：双主题 × 1600/1024 × 7 状态 = 28 状态，5 条整条候选 **0 命中**，4 个待删分支 **0 命中**。
- 计算样式：28 状态、每状态 ≤1500 个带类元素 × 32 个属性，**0 差异**。
- 截图：14 组中 **12 组 0 像素差异**；`dark-05-tab-3` 0.0983%、`light-05-tab-3` 0.0483% 与噪声基线（0.0501% / 0.0507%）同量级；`light-05-tab-1` 的 5.03% 经「删除前版本连跑两次」复现（before vs before2 同样 72488 像素），确认与 CSS 版本无关。
- lint 通过；vitest 70 文件 / 527 用例通过。

#### 4. 构建体积（同 HEAD `7d43ac3`，前后仅该 CSS 一个文件不同）

| 指标 | 删除前 | 删除后 | 差值 |
|---|---|---|---|
| 源文件字节 | 44396 | 43811 | **-585** |
| 源规则数 | 273 | 268 | -5 |
| PackagingView 路由产物 CSS 文件数 | 1 | 1 | 0 |
| PackagingView CSS raw | 172034 | 170057 | **-1977** |
| PackagingView CSS gzip | 22828 | 22587 | -241 |
| PackagingView CSS brotli | 12261 | 12154 | -107 |

累计（相对第一批之前）：raw 190766 → 170057（**-20709**）、gzip 24843 → 22587（-2256）、brotli 13067 → 12154（-913）；规则数 315 → 268。

#### 5. 下一批候选（本批未动，未跨文件扩大）

- `src/styles/media-account-view.css`：v2 判整条无消费者 **109** 条（v1 为 79，新增 30），其中 96 条可直接候选、13 条需运行时核实；另有 12 条仅部分分支无消费者。**需先在媒体账号路由做独立运行时核查**，本批不做。
- `packaging-detail-drawer.css`：`.detail-section-dimensions .detail-field`（1 条，见上）。
- `slide-up` 两条（Vue 过渡类，待单独确认生命周期后决定）。

### B 类：归属错位（不等于死代码，需逐个核实）

| 行号 | 选择器 | 实际消费者（是否自带同选择器） | 运行时元素 | 本文件副本是否生效 | 风险 | 建议 | 验证方式 |
|---|---|---|---|---|---|---|---|
| 164 | `.assets-body` | PackagingAssetPanel（无） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 470 | `.variant-carousel` | VariantCarousel（自带） | 60 | **是** | 中 | 先确认等价提供者；声明与消费者自带版本一致 | 静态引用 + 运行时匹配 |
| 477 | `.carousel-track` | VariantCarousel（自带） | 60 | 否 | 低 | 可判无效；声明与消费者自带版本一致 | 静态引用 + 运行时匹配 |
| 483 | `.carousel-track img` | VariantCarousel（自带） | 61 | 否 | 低 | 可判无效；声明与消费者自带版本一致 | 静态引用 + 运行时匹配 |
| 499 | `.carousel-prev, .carousel-next` | VariantCarousel（自带） | 2 | 否 | 低 | 可判无效；逗号选择器组，需人工比对 | 静态引用 + 运行时匹配 |
| 518 | `.carousel-prev:hover, .carousel-next:hover` | VariantCarousel（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 522 | `.carousel-prev` | VariantCarousel（自带） | 1 | 否 | 低 | 可判无效；声明与消费者自带版本**不一致** | 静态引用 + 运行时匹配 |
| 526 | `.carousel-next` | VariantCarousel（自带） | 1 | 否 | 低 | 可判无效；声明与消费者自带版本**不一致** | 静态引用 + 运行时匹配 |
| 530 | `.carousel-indicators` | VariantCarousel（自带） | 1 | 否 | 低 | 可判无效；声明与消费者自带版本**不一致** | 静态引用 + 运行时匹配 |
| 540 | `.carousel-indicators button` | VariantCarousel（自带） | 2 | 否 | 低 | 可判无效；声明与消费者自带版本**不一致** | 静态引用 + 运行时匹配 |
| 554 | `.carousel-manage` | VariantCarousel（自带） | 60 | 否 | 低 | 可判无效；声明与消费者自带版本**不一致** | 静态引用 + 运行时匹配 |
| 572 | `.carousel-manage:hover` | VariantCarousel（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 1912 | `.asset-meta` | CompanyBrandAssetsTab（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 1918 | `.asset-meta strong` | CompanyBrandAssetsTab（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 1926 | `.asset-meta small` | CompanyBrandAssetsTab（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2013 | `.variant-editor-sections` | PackagingVariantEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2020 | `.variant-form-section` | PackagingVariantEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2028 | `.variant-form-section h4` | PackagingVariantEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2035 | `.variant-form-grid` | PackagingVariantEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2041 | `.variant-form-grid label` | PackagingVariantEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2049 | `.variant-form-grid .wide` | LqDescriptions（无）、CustomerDetailPanel（无）、MediaAccountDetailModal（无）、PackagingBatchFillModal（自带）、PackagingVariantEditor（自带）、ProductParameterSection（无） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2055 | `.variant-form-grid input, .variant-form-grid textarea, .va` | PackagingVariantEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2067 | `.variant-form-grid textarea` | PackagingVariantEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2077 | `.variant-form-grid input:focus, .variant-form-grid textare` | PackagingVariantEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2083 | `.price-calculator` | PackagingVariantEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2093 | `.price-row` | PackagingVariantEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2100 | `.price-row label` | PackagingVariantEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2109 | `.price-input-group` | PackagingVariantEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2115 | `.price-input-group input` | PackagingVariantEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2125 | `.price-input-group .price-unit` | PackagingVariantEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2130 | `.price-calculation` | PackagingVariantEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2141 | `.price-calculation .calc-label` | PackagingVariantEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2146 | `.price-calculation .calc-value` | PackagingVariantEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2151 | `.price-calculation .calc-unit` | PackagingVariantEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2155 | `.price-calculation.usd` | PackagingVariantEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2160 | `.price-calculation.usd .calc-value` | PackagingVariantEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2170 | `.price-conversion` | PackagingVariantEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2180 | `.price-conversion span:first-child` | PackagingVariantEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2186 | `.price-conversion .usd-value` | PackagingVariantEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2229 | `.price-hint` | PackagingVariantEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2241 | `.price-hint > span` | PackagingVariantEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2247 | `.exchange-rate-inline` | PackagingVariantEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2256 | `.exchange-rate-inline input` | PackagingVariantEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2267 | `.exchange-rate-inline em` | PackagingVariantEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2298 | `.tag-editor-field` | PackagingTagEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2306 | `.tag-editor-field input` | PackagingTagEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2316 | `.tag-editor-field input:focus` | PackagingTagEditor（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2459 | `.batch-progress-bar` | ProductCatalogView（无）、PackagingBatchProgress（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2466 | `.batch-progress-fill` | ProductCatalogView（无）、PackagingBatchProgress（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2473 | `.batch-progress-meta` | PackagingBatchProgress（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2482 | `.batch-progress-status` | PackagingBatchProgress（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2486 | `.batch-progress-status.running` | H3ImportBatchPanel（无）、PackagingBatchProgress（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |
| 2490 | `.batch-progress-status.done` | FilePreviewBody（无）、LqDialog（无）、CustomerFormModal（无）、PackagingBatchProgress（自带）、PackagingEditorModal（无）、CatalogOverviewTab（无）、SidebarAssetPanel（自带） | 0 | 未出现 | 中 | 进一步核实（可能在其他交互态出现） | 静态引用 + 运行时匹配 |

### C 类：仅单组件使用（迁移可行性待试点验证，暂不迁移）

共 156 条。逐条记录略，风险点汇总见正文「C 类迁移可行性」。按使用者分布：
- PackagingDetailDrawer：65 条
- PackagingVariantCards：60 条
- PackagingSpecDetailPanel：31 条

### D 类：多组件共用（评估保留 scoped 还是模块共享）

共 61 条。使用组件数分布：
- 被 3 个组件共用：20 条
- 被 2 个组件共用：41 条
