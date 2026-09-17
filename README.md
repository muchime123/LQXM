# 产品档案管理系统

企业级工业产品数据管理平台，嵌入钉钉工作台，管理封口机及相关设备的全生命周期数据。

## 技术栈

| 层级 | 技术 |
|------|------|
| **前端** | Vue 3 (Composition API) + Vue Router 4 + Element Plus 2 + Vite 5 + TypeScript |
| **后端** | Spring Boot 3.3.5 + Spring Data JPA + SpringDoc OpenAPI + Lombok |
| **数据库** | MySQL 8.0+ (product_archive) |
| **样式** | CSS 变量设计系统 + Element Plus 覆盖，支持深色模式 |
| **文件存储** | 本地文件系统 (storage/lq-files/) |

## 目录结构

```
├── backend/          Spring Boot 后端
│   ├── src/main/java/com/liqiang/archive/
│   │   ├── config/       配置 (CORS)
│   │   ├── controller/   REST API 控制器 (14)
│   │   ├── service/      业务逻辑层 (18)
│   │   ├── repository/   JPA 仓库 (34)
│   │   ├── entity/       数据库实体 (34)
│   │   ├── dto/          数据传输对象 (9)
│   │   └── util/         工具类
│   ├── src/main/resources/application.yml
│   └── src/main/resources/db/migration/  数据库迁移
├── frontend/         Vue 3 前端
│   ├── src/
│   │   ├── api/           API 调用层
│   │   ├── components/    Vue 组件
│   │   ├── composables/   组合式函数 (28)
│   │   ├── constants/     常量定义
│   │   ├── router/        路由配置 (14 条)
│   │   ├── types/         TypeScript 类型
│   │   ├── utils/         工具函数 (16)
│   │   ├── views/         页面视图 (14)
│   │   ├── styles/        样式系统 (56 个模块)
│   │   ├── App.vue
│   │   └── main.ts
│   ├── index.html
│   ├── vite.config.ts
│   └── package.json
├── scripts/          运维和实用脚本
├── docs/             文档和数据库脚本
├── data/             数据文件 (模板、报告、定价)
├── storage/          文件存储目录
└── tmp/              临时文件
```

## 快速启动

### 1. 数据库

确保 MySQL 8.0+ 运行，创建数据库 `product_archive`。本机密码建议通过环境变量 `DB_PASSWORD` 提供，仓库内只保留示例占位值。

后端启动前还需要配置 `CRYPTO_SECRET_KEY`，它必须是解码后 32 字节的 Base64 字符串。PowerShell 可用下面的命令生成：

```powershell
$rng = [Security.Cryptography.RNGCryptoServiceProvider]::Create()
$bytes = New-Object byte[] 32
$rng.GetBytes($bytes)
$rng.Dispose()
[Convert]::ToBase64String($bytes)
```

如需在本机桌面模式使用“打开所在文件夹”，设置 `LOCAL_OPEN_FOLDER_ENABLED=true`；服务器和 Docker 部署保持默认关闭。

### 2. 后端 (JDK 21)

```bash
cd backend
mvn spring-boot:run
```

也可以使用本地启动脚本同时拉起前后端：

```powershell
$env:DB_PASSWORD = '你的本机数据库密码'
.\scripts\start-local.ps1
.\scripts\check-health.ps1
```

接口: http://localhost:8081 | Swagger: http://localhost:8081/swagger-ui.html

### 3. 前端 (Node.js 18+)

```bash
cd frontend
npm install
npm run dev      # 开发模式 http://localhost:5173
npm run build    # 生产构建
npm run lint     # ESLint 检查
npm run format   # Prettier 格式化
npm run typecheck  # TypeScript 类型检查
```

## 开发规范

详情见 [AGENTS.md](AGENTS.md)。

- Vue 组件: Composition API, `<script setup>` 语法
- TypeScript: 逐步采用，新文件使用 `.ts`
- CSS: 使用 `styles/` 中的 CSS 变量设计系统
- 数据库迁移: `V{主版}_{次版}__{描述}.sql` (Flyway 格式)
- DTO: Java 内联 record + 静态 `from()` 工厂方法
- 常量: 统一在 `frontend/src/constants/` 中管理

## 核心功能

- 产品目录 CRUD、分页搜索、批量操作
- 图片/说明书/图纸上传和管理
- Excel 导入导出
- 产品健康度评分
- 专利证书管理 + 缴费提醒
- 公司信息管理 (地址、账户、资质、团队)
- 自媒体账号管理 + 数据分析
- 文档管理 (知识库)
- 说明书模板编辑器
- 密码管理
- 审计日志
- 回收站

## 局域网访问

详见 [LAN_ACCESS_GUIDE.md](LAN_ACCESS_GUIDE.md) 或运行：

```powershell
.\scripts\setup-lan-access.ps1
```
