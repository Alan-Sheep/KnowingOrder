/**
 * 全局唯一的登录态。
 *
 * 后端只有一份会话（Tomcat 内存里的 session，靠 JSESSIONID 指向），但前端曾经有 4 份副本：
 * 工作台、企业问答、员工事务、知识空间各存一个 user ref，切一次页各问一次 /api/auth/me。
 * 结果是登录或登出后别的页面不知道、要加一条全局规则得改四个地方。
 *
 * 这里把状态提到模块顶层——模块只执行一次，所以整个应用共用同一份；
 * 页面切换直接读缓存，不再重复请求。后端仍会在会话过期时返回 401，
 * 届时调用 markAnonymous() 把全站同步为未登录。
 */
import { readonly, ref } from 'vue';
import { createEnterpriseClient, EnterpriseError } from './enterprise';
import type { Account } from './enterprise';

const api = createEnterpriseClient();

/** 已确认的账号；null 表示已确认未登录。是否确认过看 settled。 */
const account = ref<Account | null>(null);
let settled = false;
let pending: Promise<Account | null> | null = null;

export function useSession() {
  /**
   * 取当前账号。整个会话只问后端一次，并发调用共用同一个请求。
   * 未登录（401）是正常状态，返回 null 交给页面显示引导，不算错误；
   * 网络中断、数据格式不对等照常抛出，页面可以展示原因并重试。
   */
  async function ensure(): Promise<Account | null> {
    if (settled) return account.value;
    if (pending) return pending;
    pending = (async () => {
      try {
        const current = await api.me();
        account.value = current;
        settled = true;
        return current;
      } catch (error) {
        if (error instanceof EnterpriseError && error.code === 401) {
          account.value = null;
          settled = true;
          return null;
        }
        throw error;
      } finally {
        pending = null;
      }
    })();
    return pending;
  }

  /** 登录成功后立刻写入共享状态，其它页面下次进入即可直接读到。 */
  async function login(username: string, password: string): Promise<Account> {
    const current = await api.login(username, password);
    account.value = current;
    settled = true;
    return current;
  }

  async function logout(): Promise<void> {
    await api.logout();
    account.value = null;
    settled = true;
  }

  /** 任何请求收到 401 时调用：会话已失效，全站同时变为未登录。 */
  function markAnonymous(): void {
    account.value = null;
    settled = true;
  }

  return { user: readonly(account), ensure, login, logout, markAnonymous };
}
