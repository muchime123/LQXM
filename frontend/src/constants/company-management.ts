export type CompanyManagementTabKey = 'overview' | 'assets' | 'accounts' | 'kb' | 'milestones'

export interface CompanyManagementTab {
  key: CompanyManagementTabKey
  label: string
  icon: string
  description: string
}

export const COMPANY_MANAGEMENT_TABS: CompanyManagementTab[] = [
  {
    key: 'overview',
    label: '公司概览',
    description: '统一展示公司身份、经营信息、联系方式、统计卡片和基础资料编辑入口。',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>'
  },
  {
    key: 'assets',
    label: '品牌资产',
    description: '管理公司 Logo、品牌图片、宣传资料和可复用对外素材。',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18"><path d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"/></svg>'
  },
  {
    key: 'accounts',
    label: '财务账户',
    description: '维护对公、对私、退税和国际收款账户，含收款码附件。',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18"><rect x="2" y="5" width="20" height="14" rx="2"/><line x1="2" y1="10" x2="22" y2="10"/></svg>'
  },
  {
    key: 'kb',
    label: '知识库',
    description: '沉淀公司制度、标准话术、品牌资料和内部知识文章。',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18"><path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"/><path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"/></svg>'
  },
  {
    key: 'milestones',
    label: '发展历程',
    description: '记录公司重要年份、关键事件、证照节点和发展阶段。',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>'
  }
]
