const line = '    @PutMapping("/info")\r'
const tests = [
  ['^@', /^@/],
  ['^\\s*@', /^\s*@/],
  ['^\\s*@PutMapping', /^\s*@PutMapping/],
  ['^\\s*@PutMapping\\(', /^\s*@PutMapping\(/],
  ['^\\s*@PutMapping\\(.*$', /^\s*@PutMapping\(.*$/],
  ['^\\s*@PutMapping\\(.*', /^\s*@PutMapping\(.*/],
]
for (const [name, re] of tests) {
  console.log(name, '=>', JSON.stringify(re.exec(line)))
}
