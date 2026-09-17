import { spawnSync } from 'node:child_process'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const isWindows = process.platform === 'win32'

function run(label, command, args, cwd) {
  console.log(`\n=== ${label} ===`)
  const result = spawnSync(command, args, {
    cwd,
    env: process.env,
    stdio: 'inherit',
    shell: false
  })

  if (result.error) {
    console.error(`${label} 无法启动:`, result.error.message)
    process.exit(1)
  }
  if (result.status !== 0) process.exit(result.status ?? 1)
}

const backendWrapper = resolve(projectRoot, 'backend', isWindows ? 'mvnw.cmd' : 'mvnw')
run(
  '后端统一文件行为测试',
  isWindows ? process.env.ComSpec ?? 'cmd.exe' : backendWrapper,
  isWindows ? ['/d', '/s', '/c', backendWrapper, 'test'] : ['test'],
  resolve(projectRoot, 'backend')
)
run(
  '前端统一文件行为验证',
  isWindows ? process.env.ComSpec ?? 'cmd.exe' : 'npm',
  isWindows
    ? ['/d', '/s', '/c', 'npm.cmd', 'run', 'verify:file-behavior']
    : ['run', 'verify:file-behavior'],
  resolve(projectRoot, 'frontend')
)

console.log('\n统一文件行为验证全部通过。')
