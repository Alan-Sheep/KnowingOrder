import { EnterpriseError, positiveId } from './enterprise.ts';
import { pageView } from './knowledge.ts';
import type { PageData } from './knowledge';

export interface Department { id: string; name: string; status: number }
export interface OrganizationMember { id: string; userId: string; username: string; departmentId: string | null;
  role: string; status: number; accountStatus: number; permissionVersion: number }
export interface Departments { enterprisePermissionVersion: number; page: PageData<Department> }
const object = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
export function organizationVersion(v: unknown): number {
  if (!Number.isSafeInteger(v) || Number(v) < 1) throw new EnterpriseError('组织版本不正确，请刷新核对。');
  return Number(v);
}
export function departmentName(v: string): string {
  const clean = v.trim();
  if (!clean || clean.length > 128 || /[\u0000-\u001f\u007f-\u009f\u2028\u2029]/.test(clean)) throw new EnterpriseError('部门名称须为1至128字符，不能含控制符或换行。');
  return clean;
}
export function departmentView(v: unknown): Department {
  if (!object(v) || !positiveId(v.id) || typeof v.name !== 'string' || !v.name || v.name.length > 128 || ![0, 1].includes(v.status as number)) throw new EnterpriseError('部门数据没能正确读取，请稍后重试。');
  return { id: v.id, name: v.name, status: v.status as number };
}
export function memberView(v: unknown): OrganizationMember {
  if (!object(v) || !positiveId(v.id) || !positiveId(v.userId) || typeof v.username !== 'string' || !v.username || v.username.length > 64
    || !(v.departmentId === null || positiveId(v.departmentId)) || !['ADMIN', 'EMPLOYEE'].includes(v.role as string)
    || ![0, 1, 2].includes(v.status as number) || ![0, 1].includes(v.accountStatus as number)) throw new EnterpriseError('成员数据没能正确读取，请稍后重试。');
  return { id: v.id, userId: v.userId, username: v.username, departmentId: v.departmentId, role: v.role as string,
    status: v.status as number, accountStatus: v.accountStatus as number, permissionVersion: organizationVersion(v.permissionVersion) };
}
export function departmentsView(v: unknown): Departments {
  if (!object(v)) throw new EnterpriseError('组织数据没能正确读取，请稍后重试。');
  const page = pageView(v.page, departmentView);
  if (page.pageSize > 100) throw new EnterpriseError('组织页大小不正确。');
  return { enterprisePermissionVersion: organizationVersion(v.enterprisePermissionVersion), page };
}
export function membersView(v: unknown): PageData<OrganizationMember> {
  if (!object(v)) throw new EnterpriseError('成员列表格式不正确。');
  const page = pageView(v.page, memberView);
  if (page.pageSize > 100) throw new EnterpriseError('成员页大小不正确。');
  return page;
}
export function departmentResult(v: unknown) {
  if (!object(v)) throw new EnterpriseError('部门操作响应不正确。');
  return { enterprisePermissionVersion: organizationVersion(v.enterprisePermissionVersion), department: departmentView(v.department) };
}
export function employeeResult(v: unknown) {
  if (!object(v) || !positiveId(v.userId) || !positiveId(v.memberId) || !positiveId(v.enterpriseId)) throw new EnterpriseError('开户数据没能正确读取，请稍后重试。');
  return { userId: v.userId, memberId: v.memberId, enterpriseId: v.enterpriseId };
}
