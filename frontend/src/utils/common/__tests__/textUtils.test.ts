import { describe, expect, it } from 'vitest'
import { digitsOnly, maskString, tagList, truncate } from '../textUtils'

describe('text utils', () => {
  it('extracts only digits from a patent number', () => {
    expect(digitsOnly('ZL202521392375.7')).toBe('2025213923757')
    expect(digitsOnly('2025213923757')).toBe('2025213923757')
    expect(digitsOnly('专利号 CN123-456/7')).toBe('1234567')
  })

  it('returns empty string for falsy input', () => {
    expect(digitsOnly(null)).toBe('')
    expect(digitsOnly(undefined)).toBe('')
    expect(digitsOnly('')).toBe('')
  })

  it('truncates long strings', () => {
    expect(truncate('abcdef', 3)).toBe('abc...')
    expect(truncate('abc', 5)).toBe('abc')
  })

  it('parses tag lists', () => {
    expect(tagList('a, b；c')).toEqual(['a', 'b', 'c'])
  })

  it('masks strings', () => {
    expect(maskString('13800000000')).toBe('138****0000')
  })
})
