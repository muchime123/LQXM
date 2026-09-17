console.log('a$ on aCR:', /a$/.test('a\r'))
console.log('dot match CR:', /./.test('\r'))
const re = /^(\s*)@(PostMapping|PutMapping|DeleteMapping)(.*)$/m
const line = '    @PutMapping("/info")\r'
console.log('with m flag:', JSON.stringify(re.exec(line)))
