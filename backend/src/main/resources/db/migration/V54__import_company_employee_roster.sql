ALTER TABLE company_team_members
    ADD COLUMN phone VARCHAR(20) NULL COMMENT '手机号码' AFTER department;

-- 清理由初始演示迁移写入、且不在当前员工名单中的占位成员。
DELETE FROM company_team_members
WHERE (name = '陈杰' AND position = '技术总监')
   OR (name = '陈路平' AND position = '生产总监')
   OR (name = '陈权' AND position = '销售总监')
   OR (name = '周哲' AND position = '售后经理');

-- 保留已有核心成员资料，仅用当前员工名单补齐联系方式、部门和排序。
UPDATE company_team_members
SET phone = '13736360704', department = '办公室', sort_order = 1
WHERE name = '于士强';

INSERT INTO company_team_members
    (name, phone, department, is_core, sort_order, created_at, updated_at)
SELECT roster.name, roster.phone, roster.department, FALSE, roster.sort_order,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM (
    SELECT '于士强' name, '13736360704' phone, '办公室' department, 1 sort_order
    UNION ALL SELECT '李晓梅', '18857857643', '办公室', 2
    UNION ALL SELECT '邵富臻', '13757736564', '办公室', 3
    UNION ALL SELECT '董梦津', '18658387537', '办公室', 4
    UNION ALL SELECT '李南', '15961303383', '办公室', 5
    UNION ALL SELECT '于伟', '18133083335', '办公室', 6
    UNION ALL SELECT '杜子月', '16630228769', '办公室', 7
    UNION ALL SELECT '林琳琳', '18867741931', '办公室', 8
    UNION ALL SELECT '李文静', '13426515703', '办公室', 9
    UNION ALL SELECT '徐恩永', '15905775580', '办公室', 10
    UNION ALL SELECT '胡哲尔', '13857767459', '办公室', 11
    UNION ALL SELECT '陈圆缘', '15869635611', '办公室', 12
    UNION ALL SELECT '田丰彩', '13732044544', '办公室', 13
    UNION ALL SELECT '刘娜', '18858806267', '办公室', 14
    UNION ALL SELECT '高雪梅', '15067833632', '办公室', 15
    UNION ALL SELECT '刘杰', '15572510905', '办公室', 16
    UNION ALL SELECT '胡健', '13868642042', '办公室', 17
    UNION ALL SELECT '李水云', '13738716829', '办公室', 18
    UNION ALL SELECT '叶宪克', '13004733733', '办公室', 19
    UNION ALL SELECT '段亚威', '13619890455', '办公室', 20
    UNION ALL SELECT '王勃', '17315910926', '办公室', 21
    UNION ALL SELECT '邓雨婷', '18756717053', '办公室', 22
    UNION ALL SELECT '孙湘缘', '18235530730', '办公室', 23
    UNION ALL SELECT '于洋洋', '16655239635', '办公室', 24
    UNION ALL SELECT '朱彩芬', '13634235337', '办公室', 25
    UNION ALL SELECT '张志平', '17701792584', '办公室', 26
    UNION ALL SELECT '章海宝', '18857529204', '办公室', 27
    UNION ALL SELECT '徐青松', '19883739121', '办公室', 28
    UNION ALL SELECT '吴梦星', '13958752443', '办公室', 29
    UNION ALL SELECT '周岚清', '18040490402', '办公室', 30
    UNION ALL SELECT '李志', '15385498525', '办公室', 31
    UNION ALL SELECT '王彬', '15713831760', '办公室', 32
    UNION ALL SELECT '陈轩', '15868503936', '办公室', 33
    UNION ALL SELECT '李玉强', '17545140583', '半自动气调机', 34
    UNION ALL SELECT '严帮华', '15869395268', '半自动气调机', 35
    UNION ALL SELECT '陈全忠', '13874536436', '半自动气调机', 36
    UNION ALL SELECT '寿砚羽', '13757894565', '半自动气调机', 37
    UNION ALL SELECT '柯晓定', '18367812203', '半自动气调机', 38
    UNION ALL SELECT '余尔用', '13506510799', '半自动气调机', 39
    UNION ALL SELECT '胡奕宗', '15705875751', '半自动气调机', 40
    UNION ALL SELECT '马仪炜', '13855882223', '半自动气调机', 41
    UNION ALL SELECT '张太国', '19300245534', '半自动气调机', 42
    UNION ALL SELECT '韩玉', '17754782778', '半自动气调机', 43
    UNION ALL SELECT '林甲修', '13566232122', '全自动气调机', 44
    UNION ALL SELECT '方干', '13456000498', '全自动气调机', 45
    UNION ALL SELECT '彭成奎', '13758468517', '全自动气调机', 46
    UNION ALL SELECT '向松华', '15068292022', '全自动气调机', 47
    UNION ALL SELECT '龚淑操', '15967446976', '全自动气调机', 48
    UNION ALL SELECT '储维春', '15888767750', '全自动气调机', 49
    UNION ALL SELECT '李信镇', '18750384322', '全自动气调机', 50
    UNION ALL SELECT '季宏剑', '18867809267', '全自动气调机', 51
    UNION ALL SELECT '吴秀平', '15726898573', '全自动气调机', 52
    UNION ALL SELECT '袁林彬', '17367279249', '全自动气调机', 53
    UNION ALL SELECT '谢永昊', '19883270585', '全自动气调机', 54
    UNION ALL SELECT '王金良', '13185812125', '全自动气调机', 55
    UNION ALL SELECT '林桂修', '13957755460', '全自动气调机', 56
    UNION ALL SELECT '田栋辉', '15369072103', '全自动气调机', 57
    UNION ALL SELECT '杨帅', '13634259422', '全自动气调机', 58
    UNION ALL SELECT '贾召云', '13346087082', '全自动气调机', 59
    UNION ALL SELECT '罗群英', '15282106291', '全自动气调机', 60
    UNION ALL SELECT '胡爽', '13216655209', '全自动气调机', 61
    UNION ALL SELECT '汤文博', '18368772800', '全自动气调机', 62
    UNION ALL SELECT '陈希伟', '18267782012', '加工中心', 63
    UNION ALL SELECT '吴尚东', '18760210206', '加工中心', 64
    UNION ALL SELECT '占旭灵', '17276136561', '加工中心', 65
    UNION ALL SELECT '陈兵兵', '13509581860', '加工中心', 66
    UNION ALL SELECT '卢兴徽', '13806694400', '雕刻', 67
    UNION ALL SELECT '吴建华', '15057539971', '雕刻', 68
    UNION ALL SELECT '罗海丹', '15773339723', '雕刻', 69
    UNION ALL SELECT '李国文', '15858807809', '雕刻', 70
    UNION ALL SELECT '张晨阳', '18991489693', '雕刻', 71
    UNION ALL SELECT '李晖', '18879772860', '雕刻', 72
    UNION ALL SELECT '卢浩', '17701774273', '雕刻', 73
    UNION ALL SELECT '曹义山', '13566224430', '封口机', 74
    UNION ALL SELECT '李红伟', '13385676862', '封口机', 75
    UNION ALL SELECT '曹标准', '15857732936', '封口机', 76
    UNION ALL SELECT '赵振海', '18767736695', '封口机', 77
    UNION ALL SELECT '于帅帅', '13586519700', '封口机', 78
    UNION ALL SELECT '陈秀梅', '17719365109', '封口机', 79
    UNION ALL SELECT '陈炳金', '15167756263', '封口机', 80
    UNION ALL SELECT '陈应祥', '15168759005', '封口机', 81
    UNION ALL SELECT '路浩', '18298081117', '封口机', 82
    UNION ALL SELECT '张彬', '19389708486', '封口机', 83
    UNION ALL SELECT '杨秀秀', '17360800031', '封口机', 84
    UNION ALL SELECT '曹敏红', '13221367882', '封口机', 85
    UNION ALL SELECT '刘艳', '13956852983', '封口机', 86
    UNION ALL SELECT '寿亭宇', '15856717875', '封口机', 87
    UNION ALL SELECT '李廷各', '18768573544', '封口机', 88
    UNION ALL SELECT '刘君驰', '17326711084', '封口机', 89
    UNION ALL SELECT '林洁', '15057767354', '封口机', 90
    UNION ALL SELECT '冯润民', '15258198456', '封口机', 91
    UNION ALL SELECT '陆伟', '15068255639', '封口机', 92
    UNION ALL SELECT '崔松莲', '13634244960', '封口机', 93
    UNION ALL SELECT '郭富顺', '13083930599', '封口机', 94
    UNION ALL SELECT '雷新龙', '19173374869', '封口机', 95
    UNION ALL SELECT '王敏瑞', '17355749930', '封口机', 96
    UNION ALL SELECT '赵俊杰', '13155223416', '封口机', 97
    UNION ALL SELECT '张进雄', '19264979318', '封口机', 98
    UNION ALL SELECT '曹俊尚', '19810824184', '封口机', 99
    UNION ALL SELECT '曾凌风', '17816222174', '封口机', 100
    UNION ALL SELECT '万平', '15985616698', '封口机', 101
) roster
WHERE NOT EXISTS (
    SELECT 1
    FROM company_team_members existing
    WHERE existing.name = roster.name
      AND existing.phone = roster.phone
);
