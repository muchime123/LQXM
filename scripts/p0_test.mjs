const line = '    @PutMapping("/info")\r'
const re = /^(\s*)@(PostMapping|PutMapping|DeleteMapping)(.*)$/
console.log('test1:', JSON.stringify(re.exec(line)))
const line2 = '    @PutMapping("/info")\r'
const re2 = /^(\s*)@(PutMapping)(.*)$/
console.log('test2:', JSON.stringify(re2.exec(line2)))
const line3 = '    @PutMapping("/info")'
console.log('test3 noCR:', JSON.stringify(re.exec(line3)))
