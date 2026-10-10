-- 本地开发/演示环境初始化白名单。实际用户身份与权限由用户中心提供。

INSERT INTO reviewer_whitelist (review_role, employee_no, display_name, email, mobile, department_name, created_by_employee_no) VALUES
    ('HARDWARE_EXPERT', 'BMS002', '刘满红', 'liu.manhong@bms.example.com', '13800000002', 'BMS硬件部', 'BMS001'),
    ('PCB_EXPERT', 'BMS003', '王腾飞', 'wang.tengfei@bms.example.com', '13800000003', 'BMS硬件部', 'BMS001'),
    ('STRUCTURE_EXPERT', 'BMS004', '章俊', 'zhang.jun@bms.example.com', '13800000004', 'BMS硬件部', 'BMS001'),
    ('PROCESS_EXPERT', 'BMS005', '陈远杰', 'chen.yuanjie@bms.example.com', '13800000005', 'BMS硬件部', 'BMS001'),
    ('PCB_MUTUAL_CHECK', 'BMS006', '李少才', 'li.shaocai@bms.example.com', '13800000006', 'BMS硬件部', 'BMS001'),
    ('SCHEMATIC_MUTUAL_CHECK', 'BMS007', '李阳', 'li.yang@bms.example.com', '13800000007', 'BMS硬件部', 'BMS001'),
    ('EMC_EXPERT', 'BMS009', '张腾瑜', 'zhang.tengyu@bms.example.com', '13800000009', 'BMS硬件部', 'BMS001'),
    ('EMC_EXPERT', 'BMS010', '王世科', 'wang.shike@bms.example.com', '13800000010', 'BMS硬件部', 'BMS001'),
    ('EMC_EXPERT', 'BMS011', '郭哲', 'guo.zhe@bms.example.com', '13800000011', 'BMS硬件部', 'BMS001');
