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
