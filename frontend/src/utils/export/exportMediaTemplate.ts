/**
 * 自媒体账号导入模板下载（前端生成，无需后端）
 * 表头与后端 importFromExcel 列映射一致
 */
import * as XLSX from 'xlsx'

const HEADERS: string[] = [
  '平台',
  '链接',
  '备注',
  '最近更新日期',
  '账号名称',
  '账号ID',
  '获赞数',
  '粉丝数',
  '视频数',
  '运营人员'
]

const SAMPLE_DATA: (string | number)[][] = [
  [
    '抖音',
    'https://www.douyin.com/user/xxx',
    '【店铺账号】',
    '2026-06-01',
    '浙江利强包装科技有限公司',
    '1745194327',
    124000,
    54000,
    2942,
    '张三'
  ],
  ['视频号', 'https://channels.weixin.qq.com/xxx', '', '', '利强包装旗舰店', 'wxid_abc123', 35000, 12000, 580, '李四']
]

export function downloadMediaTemplate(): void {
  const ws = XLSX.utils.aoa_to_sheet([HEADERS, ...SAMPLE_DATA])

  ws['!cols'] = [
    { wch: 8 },
    { wch: 45 },
    { wch: 15 },
    { wch: 14 },
    { wch: 30 },
    { wch: 25 },
    { wch: 12 },
    { wch: 12 },
    { wch: 10 },
    { wch: 10 }
  ]

  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, ws, '自媒体账号')
  XLSX.writeFile(wb, '自媒体账号导入模板.xlsx')
}
