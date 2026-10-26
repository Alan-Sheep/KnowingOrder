import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

// 生成千级学校演示语料：结构与 school-knowledge.json 一致，内容确定、可重复生成。
// 仅用于规模演示，不代表真实学校规定；导入仍走真实上传、解析、发布链路。
const source = JSON.parse(await readFile(new URL('../../backend/demo/school-knowledge.json', import.meta.url), 'utf8'));
assert.equal(source.spaces.length, 6);

const total = Number(process.argv.find(value => value.startsWith('--count='))?.split('=')[1] ?? 1000);
assert.ok(Number.isInteger(total) && total >= 1 && total <= 5000, '--count 需为 1—5000 的整数');

const topics = {
  '教学教务与学籍': ['选课与退课', '课程成绩复核', '在读证明', '学籍信息变更', '缓考与补考', '教室借用', '期末考试安排', '毕业资格审核'],
  '学生事务与奖助服务': ['奖学金评定', '助学金申请', '勤工助学岗位', '宿舍调整', '心理辅导预约', '学生活动审批', '家庭困难认定', '学生申诉'],
  '图书馆与学习支持': ['图书借阅', '馆际互借', '文献传递', '研讨间预约', '电子资源访问', '逾期处理', '新生入馆培训', '论文查重'],
  '校园生活与后勤服务': ['宿舍报修', '水电缴费', '校园卡补办', '食堂意见反馈', '快递领取', '校车乘坐', '校医院就诊', '失物招领'],
  '实习实践与就业指导': ['三方协议', '实习备案', '校园招聘', '简历诊断', '就业推荐表', '档案转递', '创业咨询', '职业测评'],
  '校园网络与信息服务': ['校园网开户', 'VPN 使用', '邮箱申请', '统一身份认证', '正版软件', '数据备份', '密码重置', '网络安全事件报告']
};

const settings = {
  '教学教务与学籍': { unit: '教务处', system: '教务系统', material: '学生证和校园卡', window: '行政楼一层教务服务窗口' },
  '学生事务与奖助服务': { unit: '学生工作处', system: '学生事务平台', material: '学生证和本人银行卡信息', window: '大学生活动中心一层服务台' },
  '图书馆与学习支持': { unit: '图书馆', system: '图书馆服务平台', material: '校园卡和借阅记录', window: '图书馆一层总服务台' },
  '校园生活与后勤服务': { unit: '后勤服务中心', system: '校园服务平台', material: '校园卡和宿舍信息', window: '后勤服务大厅' },
  '实习实践与就业指导': { unit: '就业指导中心', system: '就业服务平台', material: '学生证和实习/就业材料', window: '就业指导中心咨询室' },
  '校园网络与信息服务': { unit: '信息化办公室', system: '统一身份认证平台', material: '校园卡和本人联系方式', window: '信息化服务窗口' }
};

const campuses = ['主校区', '东校区', '南校区'];
const terms = ['春季学期', '秋季学期'];
const audiences = ['本科生', '研究生', '新生', '毕业年级'];
const years = [2021, 2022, 2023, 2024, 2025, 2026];

function sectionsOf(space, topic, year, term, campus, audience, serial) {
  const info = settings[space];
  const wait = 3 + (serial % 8);
  const week = 1 + (serial % 3);
  return [
    `适用范围：${year}年${term}${campus}${topic}相关事项，由${info.unit}负责解释；适用于知序大学在籍${audience}，不代替学院或导师的审批意见。`,
    `办理时间：${year}年${term}第${week}周工作日09:00—12:00、14:00—17:00受理；线上入口为${info.system}，以系统当日显示的开放时间为准，窗口关闭后不再受理普通申请。`,
    `所需材料：本人${info.material}、事项说明及相关证明。材料须真实完整，不得上传他人证件、成绩单或未经授权的个人信息。`,
    `办理步骤：登录${info.system}，选择「${topic}」事项，核对姓名、学号或工号、联系方式后提交。提交成功以系统回执编号为准，仅保存草稿不算办结。`,
    `反馈时限：${info.unit}原则上在受理后${wait}个工作日内反馈；需要补充材料或跨部门协同时会说明原因，申请人按原通知继续等待，不重复提交。`,
    `咨询与边界：可到${info.window}咨询，或通过${info.system}查看办理进度。涉及成绩、资格、资金和隐私的结论须人工复核；本资料为演示语料，规则以学校正式文件为准。`
  ];
}

const spaces = new Map(source.spaces.map(space => [space.name, []]));
const spaceNames = [...spaces.keys()];
let serial = 0;
for (let index = 0; index < total; index++) {
  const space = spaceNames[index % spaceNames.length];
  const list = topics[space];
  const topic = list[Math.floor(index / spaceNames.length) % list.length];
  const year = years[(index * 7 + index % spaceNames.length) % years.length];
  const term = terms[index % terms.length];
  const campus = campuses[index % campuses.length];
  const audience = audiences[(index * 3) % audiences.length];
  const perSpace = spaces.get(space).length + 1;
  const title = `${year}年${term}${campus}${audience}${topic}办理指引（第${String(perSpace).padStart(3, '0')}号）`;
  assert.ok(title.length <= 128, '生成的标题超过 128 字');
  spaces.get(space).push({ title, sections: sectionsOf(space, topic, year, term, campus, audience, ++serial) });
}

const output = {
  edition: source.edition,
  notice: source.notice,
  spaces: [...spaces].map(([name, documents]) => ({ name, documents }))
};
const titles = output.spaces.flatMap(space => space.documents.map(doc => doc.title));
assert.equal(new Set(titles).size, titles.length, '生成的标题存在重复');

const target = fileURLToPath(new URL('../.tmp/school-corpus-bulk.json', import.meta.url));
await mkdir(fileURLToPath(new URL('../.tmp/', import.meta.url)), { recursive: true });
await writeFile(target, `${JSON.stringify(output, null, 2)}\n`, 'utf8');
for (const space of output.spaces) console.log(`${space.name}：${space.documents.length} 篇`);
console.log(`已生成 ${titles.length} 篇演示资料：${target}`);
