import { computed, reactive } from 'vue'

const defaultRoles = 'HARDWARE_DEPARTMENT_MANAGER,PCB_LEADER,SCHEMATIC_LEADER,HARDWARE_EXPERT,EMC_EXPERT,DESIGNER,PROCESS_EXPERT,STRUCTURE_EXPERT,PCB_MUTUAL_CHECK,SCHEMATIC_MUTUAL_CHECK'

export const identity = reactive({
  employeeNo: 'BMS001',
  displayName: '王鹏飞',
  email: '',
  mobile: '',
  departmentName: '',
  roles: defaultRoles
})

export const roleList = computed(() => identity.roles.split(',').map((value) => value.trim()).filter(Boolean))

export function saveIdentity(): void {
  // 当前用户由后端用户中心获取，前端不保存或传递身份信息。
}
