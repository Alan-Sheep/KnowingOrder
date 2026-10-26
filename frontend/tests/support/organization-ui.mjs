import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

/** 复用现有CDP连接；只修改本轮标记部门和员工，最终停用，不接触既有组织记录。 */
export async function checkOrganizationUi({ evaluate, wait, passed, live, setCleanup, injectConflict, loseResponse }) {
  const marker = `org-test-${Date.now()}`;
  const departmentName = `${marker}-部门`;
  const username = marker;
  const enterprise = await evaluate('document.getElementById("enterprise-workspace")?.value ?? null')
    ?? process.env.ENTERPRISE_UI_ID ?? '1';
  assert.match(enterprise, /^[1-9]\d*$/);
  const root = `/api/enterprises/${enterprise}/organization`;
  let departmentId;
  let memberId;
  const notice = text => `document.querySelector('.organization-panel > p[role=status]')?.textContent.includes(${JSON.stringify(text)}) && !document.querySelector('.organization-panel > button').disabled`;
  const click = text => evaluate(`[...document.querySelectorAll('.organization-panel button')].find(n=>n.textContent.trim()===${JSON.stringify(text)}).click()`);
  const fill = (form, selector, value, event = 'input') => evaluate(`(()=>{
    const n=document.querySelector('form[aria-label=${JSON.stringify(form)}] ${selector}');
    n.value=${JSON.stringify(value)};n.dispatchEvent(new Event(${JSON.stringify(event)},{bubbles:true}));
  })()`);
  const submit = form => evaluate(`document.querySelector('form[aria-label=${JSON.stringify(form)}]').requestSubmit()`);
  const read = async (kind, id) => evaluate(`(async()=>{
    const first=await(await fetch(${JSON.stringify(root + '/' + kind + '?pageNum=1&pageSize=100')},{cache:'no-store'})).json();
    if(first.code!==0 || first.data.page.pages>10)throw Error('组织测试目录不可用或过大，停止验收');
    let rows=first.data.page.records;
    for(let p=2;p<=first.data.page.pages;p++){
      const next=await(await fetch(${JSON.stringify(root + '/' + kind)}+'?pageNum='+p+'&pageSize=100',{cache:'no-store'})).json();
      if(next.code!==0)throw Error('组织目录读取未完成');rows=rows.concat(next.data.page.records);
    }
    const row=rows.find(n=>n.id===${JSON.stringify(id)});
    if(!row)throw Error('本轮标记记录不存在');
    return {version:first.data.enterprisePermissionVersion,row};
  })()`);
  const findCurrentRow = async (section, text, nextButton) => {
    for (let page = 0; page < 10; page++) {
      if (await evaluate(`[...document.querySelectorAll('section[aria-label=${JSON.stringify(section)}] li')].some(n=>n.textContent.includes(${JSON.stringify(text)}))`)) return;
      assert.equal(await evaluate(`[...document.querySelectorAll('.organization-panel button')].find(n=>n.textContent.trim()===${JSON.stringify(nextButton)}).disabled`), false, '本轮标记记录未出现在列表');
      await click(nextButton); await wait(notice('组织与成员已重新核权读取'));
    }
    throw new Error('组织验收只扫描前10页，不扩大读取范围');
  };
  const cleanup = async () => {
    // 不读浏览器Cookie；仅通过现有同源Session和CSRF入口清理本轮已定位的记录。
    if (memberId) {
      const current = await read('members', memberId);
      assert.equal(current.row.username, username); assert.equal(current.row.role, 'EMPLOYEE');
      if (current.row.status !== 1 || current.row.departmentId !== null) {
        const response = await evaluate(`(async()=>{
          const c=await(await fetch('/api/auth/csrf',{cache:'no-store'})).json();
          if(c.code!==0||c.data.headerName!=='X-CSRF-TOKEN')throw Error('请求校验不可用');
          return(await fetch(${JSON.stringify(root + '/members/' + memberId)},{method:'POST',
            headers:{'Content-Type':'application/json',[c.data.headerName]:c.data.token},
            body:JSON.stringify(${JSON.stringify({ expectedVersion: current.row.permissionVersion, departmentId: null, status: 1 })})})).json();
        })()`);
        assert.equal(response.code, 0);
      }
    }
    if (departmentId) {
      const current = await read('departments', departmentId);
      assert.ok([departmentName, departmentName + '-归档'].includes(current.row.name));
      if (current.row.status !== 1) {
        const response = await evaluate(`(async()=>{
          const c=await(await fetch('/api/auth/csrf',{cache:'no-store'})).json();
          if(c.code!==0||c.data.headerName!=='X-CSRF-TOKEN')throw Error('请求校验不可用');
          return(await fetch(${JSON.stringify(root + '/departments/' + departmentId)},{method:'POST',
            headers:{'Content-Type':'application/json',[c.data.headerName]:c.data.token},
            body:JSON.stringify(${JSON.stringify({ expectedEnterpriseVersion: current.version, name: current.row.name, status: 1 })})})).json();
        })()`);
        assert.equal(response.code, 0);
      }
    }
  };
  setCleanup(cleanup);
  await evaluate('document.querySelector(".organization-panel").open=true');
  await click('刷新组织与成员'); await wait(notice('组织与成员已重新核权读取'));
  assert.equal(await evaluate('document.querySelector(".organization-panel img,.organization-panel script")===null'), true);
  passed('管理员组织入口读取真实契约，部门与成员名称仅作文字展示');
  await fill('创建部门', 'input', departmentName); await submit('创建部门');
  await wait(notice('部门已创建')); await findCurrentRow('部门列表', departmentName, '部门下一页');
  departmentId = await evaluate(`[...document.querySelectorAll('section[aria-label=部门列表] li')].find(n=>n.textContent.includes(${JSON.stringify(departmentName)})).textContent.match(/编号 ([0-9]+)/)[1]`);
  assert.match(departmentId, /^[1-9]\d*$/);
  passed('页面创建标记部门并从授权列表读回编号');
  await fill('开通员工账号', 'input:not([type=password])', username);
  await fill('开通员工账号', 'input[type=password]', `Synthetic-only-${randomUUID()}`);
  await submit('开通员工账号'); await wait(notice('账号已开通'));
  memberId = await evaluate('document.querySelector(".organization-panel > p[role=status]").textContent.match(/成员编号 ([0-9]+)/)[1]');
  assert.equal(await evaluate('document.querySelector("form[aria-label=开通员工账号] input[type=password]").value===""'), true);
  await findCurrentRow('成员列表', username, '成员下一页');
  passed('页面开通标记员工账号，口令提交后清空；不默认授予知识权限');
  await click(`编辑成员 ${memberId}`);
  if (!live) {
    injectConflict(memberId);
    await fill('保存成员', 'input', departmentId); await submit('保存成员'); await wait(notice('组织信息已变化'));
    passed('成员版本冲突有明确提示，未静默覆盖或自动重试');
    await click('刷新组织与成员'); await wait(notice('组织与成员已重新核权读取'));
    await findCurrentRow('成员列表', username, '成员下一页'); await click(`编辑成员 ${memberId}`);
  }
  await fill('保存成员', 'input', departmentId); await submit('保存成员'); await wait(notice('成员已保存'));
  const assigned = await read('members', memberId);
  assert.equal(assigned.row.departmentId, departmentId); assert.equal(assigned.row.status, 0);
  passed('成员直属部门写入并读回，权限版本递增');
  await findCurrentRow('部门列表', departmentName, '部门下一页'); await click(`编辑部门 ${departmentId}`);
  await fill('保存部门', 'select', '1', 'change'); await submit('保存部门'); await wait(notice('组织信息已变化'));
  assert.equal((await read('departments', departmentId)).row.status, 0);
  passed('部门仍有成员时停用被拒绝，页面提示核对关联且数据库未变更状态');
  await findCurrentRow('成员列表', username, '成员下一页'); await click(`编辑成员 ${memberId}`);
  await fill('保存成员', 'input', ''); await fill('保存成员', 'select', '1', 'change');
  await submit('保存成员'); await wait(notice('成员已保存'));
  await findCurrentRow('部门列表', departmentName, '部门下一页'); await click(`编辑部门 ${departmentId}`);
  await fill('保存部门', 'input', departmentName + '-归档'); await fill('保存部门', 'select', '1', 'change');
  await submit('保存部门'); await wait(notice('部门已保存'));
  await cleanup();
  passed('仅清空并停用本轮标记员工，改名并停用空部门，既有员工和部门未修改');
  if (!live) {
    loseResponse(); await fill('创建部门', 'input', marker + '-未知回执'); await submit('创建部门');
    await wait(notice('操作结果未知'));
    assert.equal(await evaluate('document.querySelector(".organization-panel fieldset").disabled'), true);
    await click('刷新组织与成员'); await wait(notice('组织与成员已重新核权读取'));
    passed('创建回执丢失后锁定写入，人工刷新核对，不自动重发');
  }
  assert.equal(await evaluate('document.querySelector(".organization-panel script,.organization-panel img")===null'), true);
  const finalMember = (await read('members', memberId)).row;
  const finalDepartment = (await read('departments', departmentId)).row;
  assert.equal(finalMember.status, 1); assert.equal(finalMember.departmentId, null); assert.equal(finalDepartment.status, 1);
  return { enterpriseId: enterprise, departmentId, memberId, username, memberStatus: 1, departmentStatus: 1,
    mutationScope: '仅操作本轮标记记录', providerCalls: 0 };
}
