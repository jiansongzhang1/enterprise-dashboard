-- FiveTech Permission Center - AWS RDS PostgreSQL deployment entry script
-- Database: PostgreSQL 16+
-- Target database: appdb
--
-- IMPORTANT:
-- 1. This script is intended for psql and a new or disposable database only.
-- 2. ry_20260819_postgresql.sql contains DROP TABLE statements and will remove
--    existing application data. Do not run it against a production database
--    that contains data you need to keep.
-- 3. Run this file from the repository root with:
--
--    psql "host=<RDS_ENDPOINT> port=5432 dbname=appdb user=appadmin sslmode=require" \
--      -W -v ON_ERROR_STOP=1 --single-transaction \
--      -f sql/aws_rds_deploy_postgresql.sql
--
-- 4. The included initialization script creates the default admin user. Change
--    its password immediately after the first successful login.
-- 5. Do not add a fixed Google Authenticator secret to this file. The product
--    flow generates a unique secret when an administrator starts binding GA.

\set ON_ERROR_STOP on

-- The canonical schema, seed data, RBAC permissions, TOTP columns and一期 menu
-- cleanup are maintained in one source file to avoid schema drift.
\ir ry_20260819_postgresql.sql

-- Detailed data dictionary. These comments are stored in PostgreSQL and can be
-- viewed from DBeaver, pgAdmin or psql with \dt+ / \dd+.
COMMENT ON TABLE sys_dept IS '组织与部门表：保存组织树、部门层级、负责人及部门联系方式；sys_user.dept_id 指向本表。联系方式仅用于通知，不参与权限判断。';
COMMENT ON TABLE sys_user IS '系统用户表：保存登录账号、密码哈希、组织归属、联系方式、Google Authenticator TOTP 状态及审计字段；禁止保存明文密码或固定生产 TOTP 密钥。';
COMMENT ON TABLE sys_post IS '岗位字典表：保存岗位名称、岗位编码和岗位状态；用于用户岗位关联和展示，不直接决定菜单权限。';
COMMENT ON TABLE sys_role IS '角色表：保存 RBAC 角色及数据权限范围；角色控制系统操作权限，data_scope 控制组织数据范围。';
COMMENT ON TABLE sys_menu IS '菜单与权限表：同时承载页面菜单、目录和按钮权限；perms 字段是后端 @PreAuthorize 使用的权限标识，例如 notification:telegram:send。';
COMMENT ON TABLE sys_user_role IS '用户角色关联表：建立用户与角色的多对多关系；用户最终权限由其有效角色和角色菜单权限共同决定。';
COMMENT ON TABLE sys_role_menu IS '角色菜单权限关联表：建立角色与 sys_menu 的多对多关系；普通角色默认拒绝高危新增、删除和角色管理权限。';
COMMENT ON TABLE sys_role_dept IS '角色部门数据范围表：当角色采用按部门或部门及子部门的数据范围时，保存允许访问的部门；用于 DataScope 过滤。';
COMMENT ON TABLE sys_user_post IS '用户岗位关联表：建立用户与岗位的多对多关系；岗位信息主要用于业务归类，不替代 RBAC 权限。';
COMMENT ON TABLE sys_oper_log IS '操作日志表：记录用户新增、修改、删除、权限配置及其他管理操作，支持结果、错误信息、请求来源和时间审计。';
COMMENT ON TABLE sys_dict_type IS '字典类型表：保存系统字典分类及状态；当前后端仍依赖字典配置，不能在未检查代码引用前删除。';
COMMENT ON TABLE sys_dict_data IS '字典数据表：保存字典分类下的具体键值、标签、排序和状态；用于前端下拉框和业务状态展示。';
COMMENT ON TABLE sys_config IS '系统参数表：保存登录、密码策略及其他运行参数；修改前应核对后端配置键，避免影响登录和安全策略。';
COMMENT ON TABLE sys_logininfor IS '登录日志表：记录登录成功、失败、退出、IP、浏览器、操作系统和时间，用于异常登录审计。';
COMMENT ON TABLE sys_job IS '定时任务表：保存旧版定时任务配置；一期未开放定时任务菜单，只有确认代码不再引用后才能删除。';
COMMENT ON TABLE sys_job_log IS '定时任务日志表：保存旧版任务执行结果、异常信息和执行时间；与 sys_job 配套使用。';

-- Post-deployment verification queries. Execute manually after the transaction
-- commits; they are intentionally comments so this file remains non-destructive
-- after a successful initialization.
-- SELECT table_name FROM information_schema.tables
-- WHERE table_schema = 'public' AND table_name LIKE 'sys_%'
-- ORDER BY table_name;
-- SELECT user_id, user_name, ga_status, ga_secret IS NOT NULL AS has_ga_secret
-- FROM sys_user ORDER BY user_id;
-- SELECT menu_id, menu_name, perms FROM sys_menu
-- WHERE perms IS NOT NULL ORDER BY menu_id;
-- SELECT COUNT(*) AS role_menu_count FROM sys_role_menu;
