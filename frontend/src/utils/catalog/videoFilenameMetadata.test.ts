import { describe, expect, it } from 'vitest'
import {
  inferVideoMetadataFromFileName,
  mergeInferredTags,
  cleanVideoTitle,
  displayVideoTitle
} from './videoFilenameMetadata'

describe('inferVideoMetadataFromFileName', () => {
  it('separates applications, content and specification clues', () => {
    const result = inferVideoMetadataFromFileName('客户现场_酸奶一出四方模具运行视频.mp4')

    expect(result.applicationTags).toEqual(['酸奶'])
    expect(result.confidence).toBe('high')
    expect(result.contentTags).toEqual([])
    expect(result.videoContentType).toBe('客户现场')
    expect(result.featureTags).toEqual([])
    expect(result.specificationHints).toEqual(['一出四', '方模具'])
  })

  it('recognizes common Chinese aliases', () => {
    const result = inferVideoMetadataFromFileName('内酯豆腐封口效果特写.MOV')

    expect(result.applicationTags).toEqual(['豆腐'])
    expect(result.contentTags).toEqual(['细节特写'])
    expect(result.videoContentType).toBe('封口效果')
  })

  it.each([
    ['酱料杯手动一出四封口机.mp4', ['酱料']],
    ['冰淇凌封口机按钮自动卷膜六.mp4', ['冰淇淋']],
    ['铝罐小罐茶茶叶罐养生茶一出四手动封口机.mp4', ['茶叶']],
    ['益生菌酵素冻干粉茶粉盖封膜机.mp4', ['益生菌/冻干粉']],
    ['冷鲜面苏打面鲜面条盒装按钮全自动卷膜封口机.mp4', ['米面小吃']],
    ['生物制剂盒医疗用品手推膜片封口机.mp4', ['医疗用品']],
    ['净菜盒锁鲜盒简易手压封口机.mp4', ['净菜']]
  ])('recognizes real catalog filename %s', (fileName, expected) => {
    expect(inferVideoMetadataFromFileName(fileName, 'device_video').applicationTags).toEqual(expected)
  })

  it.each([
    ['台式充气封口机安装.mp4', '安装调试'],
    ['片膜左右偏差如何调整.mp4', '参数调整'],
    ['按钮自动片膜机如何更换上下模具.mp4', '换模调机'],
    ['气动机如何更换线路板.mp4', '部件更换'],
    ['片膜气管堵塞如何解决.mp4', '故障处理'],
    ['吸盘脏了怎么办.mp4', '清洁维护'],
    ['推杆式封口机操作（含穿膜）.mp4', '操作教学']
  ])('classifies real operation filename %s', (fileName, expectedType) => {
    expect(inferVideoMetadataFromFileName(fileName, 'operation_video').videoContentType).toBe(expectedType)
  })

  it('defaults device cases to equipment-running content', () => {
    const result = inferVideoMetadataFromFileName('酸奶封口机手动片膜一出四.mp4', 'device_video')
    expect(result.videoContentType).toBe('设备运行')
    expect(result.applicationTags).toEqual(['酸奶'])
  })

  it('extracts technical search tags without mixing them into specification hints', () => {
    const result = inferVideoMetadataFromFileName(
      '184X128片膜封口机气动手动放膜一出十二.mp4',
      'device_video'
    )

    expect(result.contentTags).toEqual([])
    expect(result.videoContentType).toBe('设备运行')
    expect(result.featureTags).toEqual(expect.arrayContaining([
      '气动设备', '片膜封口', '手动放膜'
    ]))
    expect(result.specificationHints).toEqual(['一出十二', '184X128'])
  })

  it('flags cross-group multi-application names as conflicts without applying them', () => {
    const result = inferVideoMetadataFromFileName(
      '麻辣烫封口机按钮自动卷膜一出一100字冰淇淋封口机手推自动片膜一出九.mp4',
      'device_video'
    )

    expect(result.confidence).toBe('conflict')
    expect(result.applicationTags).toEqual([])
    expect(result.conflictedApplications).toEqual(['冰淇淋', '米面小吃'])
  })

  it('treats mixed cup-sealing names spanning groups as conflicts too', () => {
    const result = inferVideoMetadataFromFileName('冰杯果蔬饮料杯一出四按钮自动封口机.mp4')

    expect(result.confidence).toBe('conflict')
    expect(result.applicationTags).toEqual([])
    expect(result.conflictedApplications).toEqual(['冰杯', '饮料'])
  })

  it('marks same-group multi-application matches as medium confidence', () => {
    const result = inferVideoMetadataFromFileName('豆腐腐乳两用封口机.mp4')

    expect(result.confidence).toBe('medium')
    expect(result.applicationTags).toEqual(['腐乳', '豆腐'])
    expect(result.conflictedApplications).toEqual([])
  })

  it('keeps single leaf tags for dairy aliases instead of the parent label', () => {
    const result = inferVideoMetadataFromFileName('芝士盒封口机运行.mp4')

    expect(result.applicationTags).toEqual(['奶酪'])
  })
})

describe('cleanVideoTitle / displayVideoTitle', () => {
  it('strips extension and import timestamps from file names', () => {
    expect(cleanVideoTitle('酸奶封口机手动片膜一出四_20260820172522198.mp4'))
      .toBe('酸奶封口机手动片膜一出四')
  })

  it('prefers videoTitle over cleaned fileName for display', () => {
    expect(displayVideoTitle({ videoTitle: '酸奶杯一出四封口演示', fileName: 'a_20260820172522198.mp4' }))
      .toBe('酸奶杯一出四封口演示')
    expect(displayVideoTitle({ videoTitle: '', fileName: 'LQ750连续式推杆封口机安装_20260820172522198.mp4' }))
      .toBe('LQ750连续式推杆封口机安装')
  })
})

describe('mergeInferredTags', () => {
  it('preserves existing tags and removes duplicates', () => {
    expect(mergeInferredTags('客户现场，设备运行', ['设备运行', '细节特写']))
      .toBe('客户现场，设备运行，细节特写')
  })
})
