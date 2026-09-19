import { computed, reactive } from 'vue'

const storedUserId = Number(localStorage.getItem('bms.mock-user-id') ?? '1')
const storedRoles = localStorage.getItem('bms.mock-roles') ?? 'HARDWARE_DEPARTMENT_MANAGER'

export const identity = reactive({
  userId: Number.isFinite(storedUserId) && storedUserId > 0 ? storedUserId : 1,
  roles: storedRoles
})

export const roleList = computed(() => identity.roles.split(',').map((value) => value.trim()).filter(Boolean))

export function saveIdentity(): void {
  localStorage.setItem('bms.mock-user-id', String(identity.userId))
  localStorage.setItem('bms.mock-roles', identity.roles)
}
