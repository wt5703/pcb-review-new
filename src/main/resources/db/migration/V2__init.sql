-- PCB / 原理图评审平台基础初始化数据（仅用于全新数据库）

INSERT INTO role_definition (role_code, role_name, role_description, enabled) VALUES
    ('HARDWARE_DEPARTMENT_MANAGER', '研发部经理', '负责 BMS 硬件部门用户与全局评审管理。', TRUE),
    ('PCB_LEADER', 'PCB组长', '负责 PCB 评审人员分配、互检管理与 PCB 任务结束。', TRUE),
    ('SCHEMATIC_LEADER', '原理图组长', '负责原理图互检人员分配与原理图任务结束。', TRUE),
    ('HARDWARE_EXPERT', '硬件评审专家', '负责原理图硬件相关评审意见提出与确认。', TRUE),
    ('EMC_EXPERT', 'EMC专家', '负责 EMC 相关评审意见提出与确认，并可下载 PCB/原理图文件。', TRUE),
    ('DESIGNER', '设计师', '负责创建评审任务、上传设计文件和答复专家意见。', TRUE),
    ('PROCESS_EXPERT', '工艺专家', '负责工艺评审意见提出与确认。', TRUE),
    ('STRUCTURE_EXPERT', '结构专家', '负责结构评审意见提出与确认。', TRUE);

INSERT INTO user_account (id, employee_no, display_name, email, department_name, enabled) VALUES
    (1, 'BMS001', '王鹏飞', 'wang.pengfei@bms.example.com', 'BMS硬件部', TRUE),
    (2, 'BMS002', '刘满红', 'liu.manhong@bms.example.com', 'BMS硬件部', TRUE),
    (3, 'BMS003', '王腾飞', 'wang.tengfei@bms.example.com', 'BMS硬件部', TRUE),
    (4, 'BMS004', '章俊', 'zhang.jun@bms.example.com', 'BMS硬件部', TRUE),
    (5, 'BMS005', '陈远杰', 'chen.yuanjie@bms.example.com', 'BMS硬件部', TRUE),
    (6, 'BMS006', '李少才', 'li.shaocai@bms.example.com', 'BMS硬件部', TRUE),
    (7, 'BMS007', '李阳', 'li.yang@bms.example.com', 'BMS硬件部', TRUE),
    (8, 'BMS008', '赵帅', 'zhao.shuai@bms.example.com', 'BMS硬件部', TRUE),
    (9, 'BMS009', '张腾瑜', 'zhang.tengyu@bms.example.com', 'BMS硬件部', TRUE),
    (10, 'BMS010', '王世科', 'wang.shike@bms.example.com', 'BMS硬件部', TRUE),
    (11, 'BMS011', '郭哲', 'guo.zhe@bms.example.com', 'BMS硬件部', TRUE),
    (12, 'BMS012', 'A设计师', 'designer.a@bms.example.com', 'BMS硬件部', TRUE),
    (13, 'BMS013', 'B设计师', 'designer.b@bms.example.com', 'BMS硬件部', TRUE);

INSERT INTO user_role (user_id, role_code) VALUES
    (1, 'HARDWARE_DEPARTMENT_MANAGER'),
    (2, 'PCB_LEADER'),
    (3, 'SCHEMATIC_LEADER'),
    (4, 'SCHEMATIC_LEADER'),
    (5, 'SCHEMATIC_LEADER'),
    (6, 'SCHEMATIC_LEADER'),
    (7, 'SCHEMATIC_LEADER'),
    (8, 'SCHEMATIC_LEADER'),
    (9, 'EMC_EXPERT'),
    (10, 'EMC_EXPERT'),
    (11, 'EMC_EXPERT'),
    (12, 'DESIGNER'),
    (13, 'DESIGNER');
