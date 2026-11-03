/**
 * 官网「企业资质」「专利与知识产权」展示内容（官网本地维护，这两类内容唯一的公开来源）。
 *
 * 说明：
 * - 后台的资质 / 专利档案管理已下线，官网不再从内部档案或 CMS 读取这两类数据。
 * - 这里只维护「展示图层」：图片 + 展示名称 + 分类标签。
 *   专利号、申请人等档案字段不再录入——需要核实的信息以证书原图为准，不在页面另存一份副本。
 * - 需要新增 / 修改条目时直接编辑本文件即可。
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
