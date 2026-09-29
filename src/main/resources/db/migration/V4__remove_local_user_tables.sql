ALTER TABLE reviewer_whitelist ADD COLUMN IF NOT EXISTS display_name VARCHAR(100);
ALTER TABLE reviewer_whitelist ADD COLUMN IF NOT EXISTS email VARCHAR(200);
ALTER TABLE reviewer_whitelist ADD COLUMN IF NOT EXISTS mobile VARCHAR(32);
ALTER TABLE reviewer_whitelist ADD COLUMN IF NOT EXISTS department_name VARCHAR(100);

-- 只迁移 PCB 系统仍需维护的评审/互检单白名单角色；设计者、组长和管理员
-- 等登录身份改由用户中心提供，不再作为本系统本地用户数据保存。
INSERT INTO reviewer_whitelist (review_role, employee_no, display_name, email, mobile, department_name, created_by)
SELECT ur.role_code, ua.employee_no, ua.display_name, ua.email, ua.mobile, ua.department_name, 1
FROM user_role ur
JOIN user_account ua ON ua.id = ur.user_id
WHERE ur.role_code IN ('HARDWARE_EXPERT', 'EMC_EXPERT', 'PCB_EXPERT', 'PROCESS_EXPERT', 'STRUCTURE_EXPERT',
                       'PCB_MUTUAL_CHECK', 'SCHEMATIC_MUTUAL_CHECK')
  AND NOT EXISTS (
      SELECT 1 FROM reviewer_whitelist rw
      WHERE rw.review_role = ur.role_code AND rw.employee_no = ua.employee_no
  );

-- 本地初始化数据中的人员也收敛到白名单。真实环境已有的白名单记录不会
-- 被重复创建；日后人员资料由白名单管理或用户中心同步维护。
INSERT INTO reviewer_whitelist (review_role, employee_no, display_name, email, mobile, department_name, created_by)
SELECT 'HARDWARE_EXPERT', employee_no, display_name, email, mobile, department_name, 1
FROM user_account ua WHERE ua.employee_no = 'BMS002'
  AND NOT EXISTS (SELECT 1 FROM reviewer_whitelist rw WHERE rw.review_role = 'HARDWARE_EXPERT' AND rw.employee_no = ua.employee_no);
INSERT INTO reviewer_whitelist (review_role, employee_no, display_name, email, mobile, department_name, created_by)
SELECT 'PCB_EXPERT', employee_no, display_name, email, mobile, department_name, 1
FROM user_account ua WHERE ua.employee_no = 'BMS003'
  AND NOT EXISTS (SELECT 1 FROM reviewer_whitelist rw WHERE rw.review_role = 'PCB_EXPERT' AND rw.employee_no = ua.employee_no);
INSERT INTO reviewer_whitelist (review_role, employee_no, display_name, email, mobile, department_name, created_by)
SELECT 'STRUCTURE_EXPERT', employee_no, display_name, email, mobile, department_name, 1
FROM user_account ua WHERE ua.employee_no = 'BMS004'
  AND NOT EXISTS (SELECT 1 FROM reviewer_whitelist rw WHERE rw.review_role = 'STRUCTURE_EXPERT' AND rw.employee_no = ua.employee_no);
INSERT INTO reviewer_whitelist (review_role, employee_no, display_name, email, mobile, department_name, created_by)
SELECT 'PROCESS_EXPERT', employee_no, display_name, email, mobile, department_name, 1
FROM user_account ua WHERE ua.employee_no = 'BMS005'
  AND NOT EXISTS (SELECT 1 FROM reviewer_whitelist rw WHERE rw.review_role = 'PROCESS_EXPERT' AND rw.employee_no = ua.employee_no);
INSERT INTO reviewer_whitelist (review_role, employee_no, display_name, email, mobile, department_name, created_by)
SELECT 'PCB_MUTUAL_CHECK', employee_no, display_name, email, mobile, department_name, 1
FROM user_account ua WHERE ua.employee_no = 'BMS006'
  AND NOT EXISTS (SELECT 1 FROM reviewer_whitelist rw WHERE rw.review_role = 'PCB_MUTUAL_CHECK' AND rw.employee_no = ua.employee_no);
INSERT INTO reviewer_whitelist (review_role, employee_no, display_name, email, mobile, department_name, created_by)
SELECT 'SCHEMATIC_MUTUAL_CHECK', employee_no, display_name, email, mobile, department_name, 1
FROM user_account ua WHERE ua.employee_no = 'BMS007'
  AND NOT EXISTS (SELECT 1 FROM reviewer_whitelist rw WHERE rw.review_role = 'SCHEMATIC_MUTUAL_CHECK' AND rw.employee_no = ua.employee_no);

UPDATE reviewer_whitelist
SET display_name = COALESCE(display_name, (SELECT display_name FROM user_account ua WHERE ua.employee_no = reviewer_whitelist.employee_no)),
    email = COALESCE(email, (SELECT email FROM user_account ua WHERE ua.employee_no = reviewer_whitelist.employee_no)),
    mobile = COALESCE(mobile, (SELECT mobile FROM user_account ua WHERE ua.employee_no = reviewer_whitelist.employee_no)),
    department_name = COALESCE(department_name, (SELECT department_name FROM user_account ua WHERE ua.employee_no = reviewer_whitelist.employee_no))
WHERE EXISTS (SELECT 1 FROM user_account ua WHERE ua.employee_no = reviewer_whitelist.employee_no);

UPDATE reviewer_whitelist
SET display_name = COALESCE(display_name, employee_no)
WHERE display_name IS NULL;

DROP TABLE IF EXISTS user_role;
DROP TABLE IF EXISTS user_account;
DROP TABLE IF EXISTS role_definition;
