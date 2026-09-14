-- FiveTech Dashboard PostgreSQL seed data
-- PostgreSQL 16+
--
-- Execute fivetech_schema_postgresql.sql first.
-- This file inserts the current user, organization, RBAC, notification,
-- audit and compatibility data. It does not drop tables.

BEGIN;
INSERT INTO sys_dept VALUES
  (100, 0,   '0',         'FiveTech',   0, 'admin', '', '', '0', '0', 'admin', CURRENT_TIMESTAMP, '', NULL),
  (101, 100, '0,100',     'Head Office', 1, 'admin', '', '', '0', '0', 'admin', CURRENT_TIMESTAMP, '', NULL),
  (102, 100, '0,100',     'Business Unit', 2, 'admin', '', '', '0', '0', 'admin', CURRENT_TIMESTAMP, '', NULL),
  (103, 101, '0,100,101', 'Engineering', 1, 'admin', '', '', '0', '0', 'admin', CURRENT_TIMESTAMP, '', NULL),
  (104, 101, '0,100,101', 'Marketing',   2, 'admin', '', '', '0', '0', 'admin', CURRENT_TIMESTAMP, '', NULL),
  (105, 101, '0,100,101', 'QA',          3, 'admin', '', '', '0', '0', 'admin', CURRENT_TIMESTAMP, '', NULL),
  (106, 101, '0,100,101', 'Finance',     4, 'admin', '', '', '0', '0', 'admin', CURRENT_TIMESTAMP, '', NULL),
  (107, 101, '0,100,101', 'Operations',  5, 'admin', '', '', '0', '0', 'admin', CURRENT_TIMESTAMP, '', NULL),
  (108, 102, '0,100,102', 'Marketing',   1, 'admin', '', '', '0', '0', 'admin', CURRENT_TIMESTAMP, '', NULL),
  (109, 102, '0,100,102', 'Finance',     2, 'admin', '', '', '0', '0', 'admin', CURRENT_TIMESTAMP, '', NULL);
INSERT INTO sys_user VALUES
  (1, 103, 'admin', 'System Administrator', '01', '', '', '0', '', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', NULL, '0', '{}'::jsonb, '0', '0', '', NULL, NULL, 'admin', CURRENT_TIMESTAMP, '', NULL, 'Super administrator; change the initial password immediately'),
  (2, 105, 'ry',    'Test User',            '00', '', '', '0', '', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', NULL, '0', '{}'::jsonb, '0', '0', '', NULL, NULL, 'admin', CURRENT_TIMESTAMP, '', NULL, 'Test user');

INSERT INTO sys_post VALUES
  (1, 'ceo',  '董事长',   1, '0', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
  (2, 'se',   '项目经理', 2, '0', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
  (3, 'hr',   '人力资源', 3, '0', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
  (4, 'user', '普通员工', 4, '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '');
INSERT INTO sys_role VALUES
  (1, '超级管理员', 'admin',  1, '1', TRUE, TRUE, '0', '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '超级管理员'),
  (2, '普通角色',   'common', 2, '2', TRUE, TRUE, '0', '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '普通角色');
INSERT INTO sys_menu VALUES
('1', '系统管理', '0', '1', 'system',           NULL, '', '', 1, 0, 'M', '0', '0', '', 'system',   'admin', CURRENT_TIMESTAMP, '', NULL, '系统管理目录'),
('3', '系统工具', '0', '3', 'tool',             NULL, '', '', 1, 0, 'M', '0', '0', '', 'tool',     'admin', CURRENT_TIMESTAMP, '', NULL, '系统工具目录'),
('100',  '用户管理', '1',   '1', 'user',       'system/user/index',        '', '', 1, 0, 'C', '0', '0', 'system:user:list',        'user',          'admin', CURRENT_TIMESTAMP, '', NULL, '用户管理菜单'),
('101',  '角色管理', '1',   '2', 'role',       'system/role/index',        '', '', 1, 0, 'C', '0', '0', 'system:role:list',        'peoples',       'admin', CURRENT_TIMESTAMP, '', NULL, '角色管理菜单'),
('102',  '权限管理', '1',   '3', 'menu',       'system/menu/index',        '', '', 1, 0, 'C', '0', '0', 'system:menu:list',        'tree-table',    'admin', CURRENT_TIMESTAMP, '', NULL, '权限管理菜单'),
('103',  '部门管理', '1',   '4', 'dept',       'system/dept/index',        '', '', 1, 0, 'C', '0', '0', 'system:dept:list',        'tree',          'admin', CURRENT_TIMESTAMP, '', NULL, '部门管理菜单'),
('108',  '日志管理', '1',   '8', 'log',        '',                         '', '', 1, 0, 'M', '0', '0', '',                        'log',           'admin', CURRENT_TIMESTAMP, '', NULL, '日志管理菜单'),
('124',  '在线用户', '108', '3', 'online',     'monitor/online/index',     '', '', 1, 0, 'C', '0', '0', 'monitor:online:list', 'people', 'admin', CURRENT_TIMESTAMP, '', NULL, '在线用户和会话管理'),
('115',  '表单构建', '3',   '1', 'build',      'tool/build/index',         '', '', 1, 0, 'C', '0', '0', 'tool:build:list',         'build',         'admin', CURRENT_TIMESTAMP, '', NULL, '表单构建菜单'),
('117',  '系统接口', '3',   '3', 'swagger',    'tool/swagger/index',       '', '', 1, 0, 'C', '0', '0', 'tool:swagger:list',       'swagger',       'admin', CURRENT_TIMESTAMP, '', NULL, '系统接口菜单'),
('118',  '权限总览',   '1',   '8', 'permission', 'system/permission/index',  '', '', 1, 0, 'C', '0', '0', 'system:permission:view',  'lock',          'admin', CURRENT_TIMESTAMP, '', NULL, '权限总览菜单'),
('119',  '邮件发送',   '1',   '9', '',           '',                         '', '', 1, 0, 'F', '1', '0', 'notification:email:send', '#', 'admin', CURRENT_TIMESTAMP, '', NULL, '邮件通知接口权限'),
('500',  '操作日志', '108', '1', 'operlog',    'monitor/operlog/index',    '', '', 1, 0, 'C', '0', '0', 'monitor:operlog:list',    'form',          'admin', CURRENT_TIMESTAMP, '', NULL, '操作日志菜单'),
('501',  '登录日志', '108', '2', 'logininfor', 'monitor/logininfor/index', '', '', 1, 0, 'C', '0', '0', 'monitor:logininfor:list', 'logininfor',    'admin', CURRENT_TIMESTAMP, '', NULL, '登录日志菜单'),
('125',  '强制下线', '124', '1', '',           '',                         '', '', 1, 0, 'F', '1', '0', 'monitor:online:forceLogout', '#', 'admin', CURRENT_TIMESTAMP, '', NULL, '强制注销指定在线会话'),
('1000', '用户查询', '100', '1',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:user:query',          '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1001', '用户新增', '100', '2',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:user:add',            '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1002', '用户修改', '100', '3',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:user:edit',           '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1003', '用户删除', '100', '4',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:user:remove',         '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1004', '用户导出', '100', '5',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:user:export',         '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1005', '用户导入', '100', '6',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:user:import',         '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1006', '重置密码', '100', '7',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:user:resetPwd',       '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1007', '角色查询', '101', '1',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:role:query',          '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1008', '角色新增', '101', '2',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:role:add',            '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1009', '角色修改', '101', '3',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:role:edit',           '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1010', '角色删除', '101', '4',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:role:remove',         '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1011', '角色导出', '101', '5',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:role:export',         '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1012', '菜单查询', '102', '1',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:menu:query',          '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1013', '菜单新增', '102', '2',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:menu:add',            '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1014', '菜单修改', '102', '3',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:menu:edit',           '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1015', '菜单删除', '102', '4',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:menu:remove',         '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1016', '部门查询', '103', '1',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:dept:query',          '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1017', '部门新增', '103', '2',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:dept:add',            '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1018', '部门修改', '103', '3',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:dept:edit',           '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1019', '部门删除', '103', '4',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:dept:remove',         '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1020', '岗位查询', '104', '1',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:post:query',          '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1021', '岗位新增', '104', '2',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:post:add',            '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1022', '岗位修改', '104', '3',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:post:edit',           '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1023', '岗位删除', '104', '4',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:post:remove',         '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1024', '岗位导出', '104', '5',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:post:export',         '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1025', '字典查询', '105', '1', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:dict:query',          '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1026', '字典新增', '105', '2', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:dict:add',            '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1027', '字典修改', '105', '3', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:dict:edit',           '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1028', '字典删除', '105', '4', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:dict:remove',         '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1029', '字典导出', '105', '5', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:dict:export',         '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1030', '参数查询', '106', '1', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:config:query',        '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1031', '参数新增', '106', '2', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:config:add',          '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1032', '参数修改', '106', '3', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:config:edit',         '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1033', '参数删除', '106', '4', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:config:remove',       '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1034', '参数导出', '106', '5', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:config:export',       '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1035', '操作查询', '500', '1', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:operlog:query',      '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1036', '操作删除', '500', '2', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:operlog:remove',     '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1037', '日志导出', '500', '3', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:operlog:export',     '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1038', '登录查询', '501', '1', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:logininfor:query',   '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1039', '登录删除', '501', '2', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:logininfor:remove',  '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1040', '日志导出', '501', '3', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:logininfor:export',  '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1041', '账户解锁', '501', '4', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:logininfor:unlock',  '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1045', '任务查询', '110', '1', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:job:query',          '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1046', '任务新增', '110', '2', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:job:add',            '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1047', '任务修改', '110', '3', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:job:edit',           '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1048', '任务删除', '110', '4', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:job:remove',         '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1049', '状态修改', '110', '5', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:job:changeStatus',   '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('1050', '任务导出', '110', '6', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:job:export',         '#', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
('120',  'Telegram发送', '1', '10', '', '', '', '', 1, 0, 'F', '1', '0', 'notification:telegram:send', '#', 'admin', CURRENT_TIMESTAMP, '', NULL, 'Telegram通知接口权限'),
('121',  'WhatsApp发送', '1', '11', '', '', '', '', 1, 0, 'F', '1', '0', 'notification:whatsapp:send', '#', 'admin', CURRENT_TIMESTAMP, '', NULL, 'WhatsApp通知接口权限'),
('122',  '系统通知', '1', '9', 'notification', 'notification/index', '', '', 1, 0, 'C', '0', '0', 'notification:view', 'message', 'admin', CURRENT_TIMESTAMP, '', NULL, '系统通知页面'),
('123',  'Lark发送', '122', '3', '', '', '', '', 1, 0, 'F', '1', '0', 'notification:lark:send', '#', 'admin', CURRENT_TIMESTAMP, '', NULL, 'Lark通知接口权限'),

INSERT INTO sys_menu_i18n (menu_id, locale, menu_name, remark)
VALUES
  (1,   'zh-CN', '系统管理', '系统管理目录'),
  (1,   'zh-TW', '系統管理', '系統管理目錄'),
  (1,   'en-US', 'System Management', 'System management'),
  (100, 'zh-CN', '用户管理', '用户管理菜单'),
  (100, 'zh-TW', '使用者管理', '使用者管理選單'),
  (100, 'en-US', 'User Management', 'User management'),
  (101, 'zh-CN', '角色管理', '角色管理菜单'),
  (101, 'zh-TW', '角色管理', '角色管理選單'),
  (101, 'en-US', 'Role Management', 'Role management'),
  (102, 'zh-CN', '权限管理', '权限管理菜单'),
  (102, 'zh-TW', '權限管理', '權限管理選單'),
  (102, 'en-US', 'Permission Management', 'Permission management'),
  (103, 'zh-CN', '部门管理', '部门管理菜单'),
  (103, 'zh-TW', '部門管理', '部門管理選單'),
  (103, 'en-US', 'Department Management', 'Department management'),
  (108, 'zh-CN', '日志管理', '日志管理菜单'),
  (108, 'zh-TW', '日誌管理', '日誌管理選單'),
  (108, 'en-US', 'Log Management', 'Log management'),
  (124, 'zh-CN', '在线用户', '在线用户和会话管理'),
  (124, 'zh-TW', '在線使用者', '在線使用者與工作階段管理'),
  (124, 'en-US', 'Online Users', 'Online users and sessions'),
  (125, 'zh-CN', '强制下线', '强制注销指定在线会话'),
  (125, 'zh-TW', '強制下線', '強制註銷指定在線工作階段'),
  (125, 'en-US', 'Force Logout', 'Revoke the selected online session'),
  (118, 'zh-CN', '权限总览', '权限总览菜单'),
  (118, 'zh-TW', '權限總覽', '權限總覽選單'),
  (118, 'en-US', 'Permission Overview', 'Permission overview'),
  (122, 'zh-CN', '系统通知', '系统通知菜单'),
  (122, 'zh-TW', '系統通知', '系統通知選單'),
  (122, 'en-US', 'System Notifications', 'System notifications'),
  (500, 'zh-CN', '操作日志', '操作日志菜单'),
  (500, 'zh-TW', '操作日誌', '操作日誌選單'),
  (500, 'en-US', 'Operation Logs', 'Operation logs'),
  (501, 'zh-CN', '登录日志', '登录日志菜单'),
  (501, 'zh-TW', '登入日誌', '登入日誌選單'),
  (501, 'en-US', 'Login Logs', 'Login logs');
INSERT INTO sys_user_role VALUES ('1', '1'), ('2', '2');
-- 普通角色仅保留基础查看权限，不允许新增、修改、删除、导入、重置密码、角色/权限配置或系统配置。
INSERT INTO sys_role_menu VALUES
('2', '1'), ('2', '100'), ('2', '103'),
('2', '108'), ('2', '500'), ('2', '501'),
('2', '1000'), ('2', '1016'),
('2', '1035'), ('2', '1038');
INSERT INTO sys_role_menu VALUES ('1', '124'), ('1', '125');
INSERT INTO sys_role_dept VALUES ('2', '100'), ('2', '101'), ('2', '105');
INSERT INTO sys_user_post VALUES ('1', '1'), ('2', '2');
INSERT INTO sys_dict_type VALUES
(1,  '用户性别', 'sys_user_sex',        '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '用户性别列表'),
(2,  '菜单状态', 'sys_show_hide',       '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '菜单状态列表'),
(3,  '系统开关', 'sys_normal_disable',  '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '系统开关列表'),
(4,  '任务状态', 'sys_job_status',      '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '任务状态列表'),
(5,  '任务分组', 'sys_job_group',       '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '任务分组列表'),
(6,  '系统是否', 'sys_yes_no',          '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '系统是否列表'),
(9,  '操作类型', 'sys_oper_type',       '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '操作类型列表'),
(10, '系统状态', 'sys_common_status',   '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '登录状态列表');
INSERT INTO sys_dict_data VALUES
(1,  1,  '男',       '0',       'sys_user_sex',        '',   '',        'Y', '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '性别男'),
(2,  2,  '女',       '1',       'sys_user_sex',        '',   '',        'N', '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '性别女'),
(3,  3,  '未知',     '2',       'sys_user_sex',        '',   '',        'N', '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '性别未知'),
(4,  1,  '显示',     '0',       'sys_show_hide',       '',   'primary', 'Y', '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '显示菜单'),
(5,  2,  '隐藏',     '1',       'sys_show_hide',       '',   'danger',  'N', '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '隐藏菜单'),
(6,  1,  '正常',     '0',       'sys_normal_disable',  '',   'primary', 'Y', '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '正常状态'),
(7,  2,  '停用',     '1',       'sys_normal_disable',  '',   'danger',  'N', '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '停用状态'),
(8,  1,  '正常',     '0',       'sys_job_status',      '',   'primary', 'Y', '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '正常状态'),
(9,  2,  '暂停',     '1',       'sys_job_status',      '',   'danger',  'N', '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '停用状态'),
(10, 1,  '默认',     'DEFAULT', 'sys_job_group',       '',   '',        'Y', '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '默认分组'),
(11, 2,  '系统',     'SYSTEM',  'sys_job_group',       '',   '',        'N', '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '系统分组'),
(12, 1,  '是',       'Y',       'sys_yes_no',          '',   'primary', 'Y', '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '系统默认是'),
(13, 2,  '否',       'N',       'sys_yes_no',          '',   'danger',  'N', '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '系统默认否'),
(18, 99, '其他',     '0',       'sys_oper_type',       '',   'info',    'N', '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '其他操作'),
(19, 1,  '新增',     '1',       'sys_oper_type',       '',   'info',    'N', '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '新增操作'),
(20, 2,  '修改',     '2',       'sys_oper_type',       '',   'info',    'N', '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '修改操作'),
(21, 3,  '删除',     '3',       'sys_oper_type',       '',   'danger',  'N', '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '删除操作'),
(22, 4,  '授权',     '4',       'sys_oper_type',       '',   'primary', 'N', '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '授权操作'),
(23, 5,  '导出',     '5',       'sys_oper_type',       '',   'warning', 'N', '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '导出操作'),
(24, 6,  '导入',     '6',       'sys_oper_type',       '',   'warning', 'N', '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '导入操作'),
(25, 7,  '强退',     '7',       'sys_oper_type',       '',   'danger',  'N', '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '强退操作'),
(26, 8,  '生成代码', '8',       'sys_oper_type',       '',   'warning', 'N', '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '生成操作'),
(27, 9,  '清空数据', '9',       'sys_oper_type',       '',   'danger',  'N', '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '清空操作'),
(28, 1,  '成功',     '0',       'sys_common_status',   '',   'primary', 'N', '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '正常状态'),
(29, 2,  '失败',     '1',       'sys_common_status',   '',   'danger',  'N', '0', 'admin', CURRENT_TIMESTAMP, '', NULL, '停用状态');
INSERT INTO sys_config VALUES
(1, '主框架页-默认皮肤样式名称',     'sys.index.skinName',               'skin-blue',     'Y', 'admin', CURRENT_TIMESTAMP, '', NULL, '蓝色 skin-blue、绿色 skin-green、紫色 skin-purple、红色 skin-red、黄色 skin-yellow'),
(2, '用户管理-账号初始密码',         'sys.user.initPassword',            '123456',        'Y', 'admin', CURRENT_TIMESTAMP, '', NULL, '初始化密码 123456'),
(3, '主框架页-侧边栏主题',           'sys.index.sideTheme',              'theme-dark',    'Y', 'admin', CURRENT_TIMESTAMP, '', NULL, '深色主题theme-dark，浅色主题theme-light'),
(6, '用户登录-黑名单列表',           'sys.login.blackIPList',            '',              'Y', 'admin', CURRENT_TIMESTAMP, '', NULL, '设置登录IP黑名单限制，多个匹配项以;分隔，支持匹配（*通配、网段）'),
(7, '用户管理-初始密码修改策略',     'sys.account.initPasswordModify',   '1',             'Y', 'admin', CURRENT_TIMESTAMP, '', NULL, '0：初始密码修改策略关闭，没有任何提示，1：提醒用户，如果未修改初始密码，则在登录时就会提醒修改密码对话框'),
(8, '用户管理-账号密码更新周期',     'sys.account.passwordValidateDays', '0',             'Y', 'admin', CURRENT_TIMESTAMP, '', NULL, '密码更新周期（填写数字，数据初始化值为0不限制，若修改必须为大于0小于365的正整数），如果超过这个周期登录系统时，则在登录时就会提醒修改密码对话框'),
(9, '用户管理-密码字符范围',         'sys.account.chrtype',              '0',             'Y', 'admin', CURRENT_TIMESTAMP, '', NULL, '默认任意字符范围，0任意（密码可以输入任意字符），1数字（密码只能为0-9数字），2英文字母（密码只能为a-z和A-Z字母），3字母和数字（密码必须包含字母，数字）,4字母数字和特殊字符（目前支持的特殊字符包括：~!@#$%^&*()-=_+）');
INSERT INTO sys_job VALUES
(1, '系统默认（无参）', 'DEFAULT', 'ryTask.ryNoParams',        '0/10 * * * * ?', '3', '1', '1', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
(2, '系统默认（有参）', 'DEFAULT', 'ryTask.ryParams(''ry'')',  '0/15 * * * * ?', '3', '1', '1', 'admin', CURRENT_TIMESTAMP, '', NULL, ''),
(3, '系统默认（多参）', 'DEFAULT', 'ryTask.ryMultipleParams(''ry'', true, 2000L, 316.50D, 100)', '0/20 * * * * ?', '3', '1', '1', 'admin', CURRENT_TIMESTAMP, '', NULL, '');

-- Remove retired menus and all descendants. This keeps the seed compatible
-- with the historical menu list while producing the current product scope.
WITH RECURSIVE retired_menu AS (
    SELECT menu_id
    FROM sys_menu
    WHERE menu_id IN (3, 113, 114)
       OR menu_name IN (
           '系统工具', '表单构建', '系统接口',
           '通知公告', '缓存监控', '缓存列表',
           '定时任务', '服务器监控', 'Druid监控',
           '岗位管理', '参数设置', '字典管理'
       )
       OR path IN (
           'tool', 'build', 'swagger', 'notice', 'cache', 'cacheList',
           'job', 'server', 'druid', 'post', 'config', 'dict'
       )
    UNION
    SELECT child.menu_id
    FROM sys_menu child
    JOIN retired_menu parent ON child.parent_id = parent.menu_id
)
DELETE FROM sys_role_menu
WHERE menu_id IN (SELECT menu_id FROM retired_menu);

WITH RECURSIVE retired_menu AS (
    SELECT menu_id
    FROM sys_menu
    WHERE menu_id IN (3, 113, 114)
       OR menu_name IN (
           '系统工具', '表单构建', '系统接口',
           '通知公告', '缓存监控', '缓存列表',
           '定时任务', '服务器监控', 'Druid监控',
           '岗位管理', '参数设置', '字典管理'
       )
       OR path IN (
           'tool', 'build', 'swagger', 'notice', 'cache', 'cacheList',
           'job', 'server', 'druid', 'post', 'config', 'dict'
       )
    UNION
    SELECT child.menu_id
    FROM sys_menu child
    JOIN retired_menu parent ON child.parent_id = parent.menu_id
)
DELETE FROM sys_menu_i18n
WHERE menu_id IN (SELECT menu_id FROM retired_menu);

WITH RECURSIVE retired_menu AS (
    SELECT menu_id
    FROM sys_menu
    WHERE menu_id IN (3, 113, 114)
       OR menu_name IN (
           '系统工具', '表单构建', '系统接口',
           '通知公告', '缓存监控', '缓存列表',
           '定时任务', '服务器监控', 'Druid监控',
           '岗位管理', '参数设置', '字典管理'
       )
       OR path IN (
           'tool', 'build', 'swagger', 'notice', 'cache', 'cacheList',
           'job', 'server', 'druid', 'post', 'config', 'dict'
       )
    UNION
    SELECT child.menu_id
    FROM sys_menu child
    JOIN retired_menu parent ON child.parent_id = parent.menu_id
)
DELETE FROM sys_menu
WHERE menu_id IN (SELECT menu_id FROM retired_menu);

-- Reset sequences after explicit seed IDs.
ALTER TABLE sys_dept       ALTER COLUMN dept_id     RESTART WITH 200;
ALTER TABLE sys_user       ALTER COLUMN user_id     RESTART WITH 100;
ALTER TABLE sys_post       ALTER COLUMN post_id     RESTART WITH 100;
ALTER TABLE sys_role       ALTER COLUMN role_id     RESTART WITH 100;
ALTER TABLE sys_menu       ALTER COLUMN menu_id     RESTART WITH 2000;
ALTER TABLE sys_menu_i18n  ALTER COLUMN id          RESTART WITH 100;
ALTER TABLE sys_oper_log   ALTER COLUMN oper_id      RESTART WITH 100;
ALTER TABLE sys_dict_type  ALTER COLUMN dict_id      RESTART WITH 100;
ALTER TABLE sys_dict_data  ALTER COLUMN dict_code    RESTART WITH 100;
ALTER TABLE sys_config     ALTER COLUMN config_id    RESTART WITH 100;
ALTER TABLE sys_logininfor ALTER COLUMN info_id      RESTART WITH 100;
ALTER TABLE sys_job        ALTER COLUMN job_id       RESTART WITH 100;
ALTER TABLE sys_job_log    ALTER COLUMN job_log_id   RESTART WITH 100;

COMMIT;
