-- PCB / 原理图评审平台初始化数据。须在 ddl.sql 成功执行后执行。
-- 实际用户身份与权限由用户中心提供；以下仅为本地开发/演示白名单。

INSERT INTO reviewer_whitelist (employee_no, display_name, email, mobile, department_name, created_by_employee_no) VALUES
    ('BMS002', '刘满红', 'liu.manhong@bms.example.com', '13800000002', 'BMS硬件部', 'BMS001'),
    ('BMS003', '王腾飞', 'wang.tengfei@bms.example.com', '13800000003', 'BMS硬件部', 'BMS001'),
    ('BMS004', '章俊', 'zhang.jun@bms.example.com', '13800000004', 'BMS硬件部', 'BMS001'),
    ('BMS005', '陈远杰', 'chen.yuanjie@bms.example.com', '13800000005', 'BMS硬件部', 'BMS001'),
    ('BMS006', '李少才', 'li.shaocai@bms.example.com', '13800000006', 'BMS硬件部', 'BMS001'),
    ('BMS007', '李阳', 'li.yang@bms.example.com', '13800000007', 'BMS硬件部', 'BMS001'),
    ('BMS009', '张腾瑜', 'zhang.tengyu@bms.example.com', '13800000009', 'BMS硬件部', 'BMS001'),
    ('BMS010', '王世科', 'wang.shike@bms.example.com', '13800000010', 'BMS硬件部', 'BMS001'),
    ('BMS011', '郭哲', 'guo.zhe@bms.example.com', '13800000011', 'BMS硬件部', 'BMS001');

INSERT INTO reviewer_whitelist_role (employee_no, review_role, created_by_employee_no) VALUES
    ('BMS002', 'HARDWARE_EXPERT', 'BMS001'), ('BMS003', 'PCB_EXPERT', 'BMS001'),
    ('BMS004', 'STRUCTURE_EXPERT', 'BMS001'), ('BMS005', 'PROCESS_EXPERT', 'BMS001'),
    ('BMS006', 'PCB_MUTUAL_CHECK', 'BMS001'), ('BMS007', 'SCHEMATIC_MUTUAL_CHECK', 'BMS001'),
    ('BMS009', 'EMC_EXPERT', 'BMS001'), ('BMS010', 'EMC_EXPERT', 'BMS001'),
    ('BMS011', 'EMC_EXPERT', 'BMS001');
-- 白名单中人员身份统一使用 employee_no；请勿在此文件维护重复数据。
