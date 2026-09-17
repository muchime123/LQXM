-- 软件文件名映射种子数据
-- 执行方式：应用启动后 Hibernate 自动建表，手动执行此 SQL 或通过 API 导入

INSERT IGNORE INTO software_file_mappings (file_name, software_name, version, category) VALUES
-- 通讯
('WeChatWin_4.1.9.exe',                     '微信',                '4.1.9',      '通讯'),
('WeCom_5.0.8.6009.exe',                    '企业微信',            '5.0.8.6009', '通讯'),
('dingtalk_downloader.exe',                 '钉钉',                '',          '通讯'),
('QQ_9.9.30-260511_x64_01.exe',             'QQ',                 '9.9.30',     '通讯'),
-- 开发
('jdk-21_windows-x64_bin.exe',              'JDK',                '21',          '开发'),
('mysql-9.7.0-winx64.msi',                  'MySQL',              '9.7.0',       '开发'),
('apache-maven-3.9.16-bin.zip',             'Apache Maven',       '3.9.16',      '开发'),
('node-v24.16.0-x64.msi',                   'Node.js',            '24.16.0',     '开发'),
('navicat17.zip',                           'Navicat Premium',    '17',          '开发'),
-- AI编辑器
('WorkBuddy-win32-x64-user-5.1.1.30799983-ecafd59f.exe', 'WorkBuddy',        '5.1.1',       'AI编辑器'),
('Codex Installer.exe',                     'Codex',              '',            'AI编辑器'),
('TRAE_Work_CN-Setup-x64.exe',              'TRAE Work CN',       '',            'AI编辑器'),
('MiniMax Code Setup 3.0.43.exe',           'MiniMax Code',       '3.0.43',      'AI编辑器'),
('QoderSetup-x64.exe',                      'Qoder',              '',            'AI编辑器'),
('QoderWork-Setup-x64.exe',                 'Qoder Work',         '',            'AI编辑器'),
('OpenCode Desktop Installer.exe',          'OpenCode Desktop',   '',            'AI编辑器'),
('A HUB Office V1.0.1.exe',                 'A HUB Office',       '1.0.1',       'AI编辑器'),
('JVS Claw-setup.exe',                      'JVS Claw',           '',            'AI编辑器'),
-- 浏览器
('ChromeSetup.exe',                         'Chrome',             '',            '浏览器'),
('QQBrowser_Setup_x64_21.1.8717.400.exe',   'QQ浏览器',           '21.1.8717',   '浏览器'),
-- 网络
('Clash.Verge_2.4.7_autobuild.0319.c6a7a2f_x64-setup.exe', 'Clash Verge',    '2.4.7',       '网络'),
('GitHubDesktopSetup-x64.exe',              'GitHub Desktop',     '',            '网络'),
('GitHubDesktop2Chinese.exe',               'GitHub Desktop 汉化包', '',         '网络'),
-- 运营
('AlibabaSupplier(11.39.80E).exe',          '阿里巴巴供应商',     '11.39.80E',   '运营'),
('XinMeiJia-install.exe',                   '新媒体管家',         '',            '运营'),
-- 其他
('CNIPAClient_install_v1.1.9.2.exe',        'CNIPA专利局客户端',  '1.1.9.2',     '其他');
