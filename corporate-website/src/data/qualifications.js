/**
 * 官网「企业资质」「专利与知识产权」展示内容（官网本地维护，不与后台档案关联）。
 *
 * 说明：
 * - 仅展示已经核对原件并复制到官网工程的图片；不读取内部档案或官网 CMS。
 * - 需要新增 / 修改条目时直接编辑本文件即可，无需改动后台数据。
 * - 图片放在 corporate-website/public/images/，与内部档案文件分开。
 */

/** 企业资质（About 页 #credentials 区块） */
export const certifications = [
  {
    id: 1,
    name: '营业执照',
    category: '企业证照',
    image: '/images/business-license.jpg',
  },
]

/** 专利与知识产权（About 页 #patents 区块） */
export const patents = [
  {
    id: 1,
    name: '气调保鲜包装机',
    assetCategory: '专利',
    patentType: '外观设计',
    image: '/images/patent-map-packaging.png',
  },
  {
    id: 2,
    name: '全自动连续式气调保鲜包装机',
    assetCategory: '专利',
    patentType: '外观设计',
    image: '/images/patent-continuous-map-packaging.png',
  },
]
