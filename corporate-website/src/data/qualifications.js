/**
 * 官网「企业资质」「专利与知识产权」展示内容（官网本地维护，不与后台档案关联）。
 *
 * 说明：
 * - 这两块内容是官网自己的展示素材，不从后台「知识产权 / 资质合规」档案读取，
 *   也不再请求 /api/website/certifications、/api/website/patents 接口。
 * - 需要新增 / 修改条目时直接编辑本文件即可，无需改动后台数据。
 * - image 字段可选：填官网 public 下的图片路径（如 '/images/xxx.webp'）；
 *   留空则展示徽标占位。图片请放到 corporate-website/public/images/ 后引用。
 */

/** 企业资质（About 页 #credentials 区块） */
export const certifications = [
  {
    id: 1,
    name: 'ISO 9001 质量管理体系认证',
    category: '管理体系',
    description: '覆盖包装设备研发、制造与售后服务的质量管理体系认证。',
    image: '',
  },
  {
    id: 2,
    name: 'CE 产品认证',
    category: '产品认证',
    description: '出口机型适用的欧盟产品安全符合性认证。',
    image: '',
  },
  {
    id: 3,
    name: '营业执照',
    category: '企业证照',
    description: '浙江利强包装科技有限公司营业执照。',
    image: '',
  },
  {
    id: 4,
    name: '产品检测报告',
    category: '检测报告',
    description: '封口、气调、真空等机型的主要性能检测报告。',
    image: '',
  },
]

/** 专利与知识产权（About 页 #patents 区块） */
export const patents = [
  {
    id: 1,
    name: '气调保鲜包装机',
    assetCategory: '专利',
    patentType: '实用新型',
    patentNo: '',
    applicant: '',
    image: '',
  },
  {
    id: 2,
    name: '连续式封口机',
    assetCategory: '专利',
    patentType: '实用新型',
    patentNo: '',
    applicant: '',
    image: '',
  },
  {
    id: 3,
    name: '真空包装机',
    assetCategory: '专利',
    patentType: '外观设计',
    patentNo: '',
    applicant: '',
    image: '',
  },
  {
    id: 4,
    name: '包装设备控制软件',
    assetCategory: '软著',
    patentType: '',
    patentNo: '',
    applicant: '',
    image: '',
  },
]
