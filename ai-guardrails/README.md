# ai-guardrails — AI 协作护栏套件
<!-- check-encoding:ignore-mojibake （本文件举例说明乱码特征，故自我豁免） -->

> **解决的问题**：用 AI 写代码时，出现**中文乱码、注释和代码被覆盖**，修补一整天。
>
> **核心思路**：**光在提示词里写"请不要乱码"是无效的**——
> 乱码和覆盖是**机制问题**，必须用机器强制手段防。
>
> 本套件提供**四层防御 + 优先级裁决体系**，可直接复制到任何项目。

---

## 快速开始（5 分钟）

```bash
# ── 1. 复制配置文件到你的项目根目录 ──
cp ai-guardrails/.editorconfig      .
cp ai-guardrails/.gitattributes     .
cp ai-guardrails/.gitignore         .
cp ai-guardrails/AGENTS.md          .
cp ai-guardrails/CLAUDE.md          .
cp ai-guardrails/.pre-commit-config.yaml .

# ── 2. 复制检查脚本 ──
mkdir -p scripts
cp ai-guardrails/scripts/check_encoding.py scripts/

# ── 3. 【★ 最重要】设置 Python 强制 UTF-8 ──
# Windows PowerShell（永久生效）：
[Environment]::SetEnvironmentVariable('PYTHONUTF8', '1', 'User')
# Linux / macOS：
echo 'export PYTHONUTF8=1' >> ~/.bashrc

# ── 4. 初始化 Git（★ 必须在放好 .gitattributes 之后）──
git init
git add --renormalize .
git commit -m "chore: 初始化项目与 AI 协作护栏"

# ── 5. 安装提交前检查钩子 ──
pip install pre-commit
pre-commit install
```

**完成。** 现在每次 `git commit` 都会自动检查乱码和文件覆盖。

---

## 四层防御体系

```
┌──────────────────────────────────────────────────────┐
│ 第 1 层：Git —— 唯一的"后悔药"                         │
│   让 AI 改代码前先 commit，出错 1 秒回滚               │
│   → 成本最低、收益最大，只做这一层也能救命              │
├──────────────────────────────────────────────────────┤
│ 第 2 层：编码强制 —— 消灭乱码                          │
│   .editorconfig + .gitattributes + PYTHONUTF8=1      │
│   → 一次性设置，永久受益                               │
├──────────────────────────────────────────────────────┤
│ 第 3 层：AI 行为规则 —— 消灭覆盖                        │
│   AGENTS.md + CLAUDE.md：禁止整文件重写、限制改动范围   │
│   → 从源头约束 AI 行为                                 │
├──────────────────────────────────────────────────────┤
│ 第 4 层：自动检查 —— 兜底                              │
│   check_encoding.py + pre-commit 钩子                │
│   → 即使 AI 忘了规则，也会在提交时被拦截                │
└──────────────────────────────────────────────────────┘
```

> ⚠️ **如果只做一件事，做第 1 层（Git）。**

---

## 优先级体系（本套件的核心）

**规则冲突时，按此顺序裁决（高位覆盖低位）：**

```
① 安全红线（密钥、破坏性操作）        ← 不可被任何东西覆盖
        ↓
② 不破坏现有内容（防覆盖、防乱码）      ← 需用户显式确认才能突破
        ↓
③ 用户在当前对话中的明确指令
        ↓
④ 项目规范
        ↓
⑤ 正确性与可验证性
        ↓
⑥ 效率 / 速度
        ↓
⑦ 代码风格与个人偏好                  ← 最低
```

**一句话记忆**：

> ### 安全 > 不破坏 > 用户要求 > 规范 > 正确 > 快 > 好看

**四级权限**：

| 级别 | 类别 | 用户能否覆盖 | AI 该怎么做 |
|---|---|---|---|
| **P0** | 安全红线（`.env`、`git push -f`、删目录） | ❌ **绝不** | **直接拒绝** |
| **P1** | 内容保护（整文件重写、批量改、改编码） | ⚠️ **需显式确认** | **先提示风险 → 等确认** |
| **P2** | 项目规范（命名、注释语言、测试） | ✅ 可以 | 照做，但说明偏离 |
| **P3** | 风格偏好（缩进、引号、换行） | ✅ 可以 | 直接照做 |

**拿不准时的默认倾向**：

```
拿不准 → 先问清楚        （而不是"先改了再说"）
拿不准 → 精确编辑        （而不是"重写整个文件"）
拿不准 → 改一个文件      （而不是"批量改一堆"）
拿不准 → 先 git commit   （而不是"直接动手"）
```

**详见**：`AGENTS.md` 第 0 节。

---

## 文件清单

```
ai-guardrails/
├── README.md                      本文件
├── AGENTS.md                      ★ 核心规则（含优先级裁决）
├── CLAUDE.md                      Claude Code 精简版
├── .editorconfig                  编辑器编码统一
├── .gitattributes                 Git 换行符/编码统一
├── .gitignore                     忽略清单（含密钥、临时文件）
├── .pre-commit-config.yaml        提交前检查（pre-commit 框架）
├── .vscode/settings.json          VS Code 防乱码设置
├── hooks/
│   └── pre-commit                 原生 git 钩子（备选方案）
├── scripts/
│   └── check_encoding.py          ★ 乱码 + 覆盖检测脚本
└── prompts/
    ├── 01-开场白.md               每次新会话先发这个
    ├── 02-改代码.md               限定范围地让 AI 改代码
    ├── 03-救火.md                 改坏了怎么救
    └── 04-review模式.md           只报告不改（最安全）
```

---

## 日常使用流程

### 每次让 AI 改代码前（30 秒）

```bash
git status                          # 干净吗？
git add . && git commit -m "存档"    # ★ 不干净就先提交（后悔药）
```

然后发 `prompts/01-开场白.md` 的内容。

### 让 AI 改代码时

用 `prompts/02-改代码.md` 的模板，**明确限定范围**。

### AI 改完后（1 分钟）

```bash
git diff                            # ★ 唯一可信的验证方式
python scripts/check_encoding.py .  # 编码检查
```

**检查清单**：
- [ ] 有没有改动我没让它动的文件？
- [ ] 有没有文件"变小了"（疑似被覆盖）？
- [ ] 中文注释还是正常的吗？
- [ ] 跑一下测试 / 跑一下程序

### 发现问题时

用 `prompts/03-救火.md`，**第一件事是叫停 AI**。

---

## 检测脚本用法

```bash
# 检查全部
python scripts/check_encoding.py .

# 检查指定目录
python scripts/check_encoding.py src/ docs/

# 额外检查文件体积突变（防覆盖，需在 git 仓库中）
python scripts/check_encoding.py . --check-shrink

# 自动修复 BOM 和换行符
python scripts/check_encoding.py . --fix
```

**检查项**：

| # | 检测 | 防的问题 |
|---|---|---|
| 1 | 是否合法 UTF-8 | GBK 文件混入 |
| 2 | 是否含 BOM | 解析器出错 |
| 3 | 乱码特征串 | `锟斤拷`、`ï»¿` 等 |
| 4 | Latin-1 乱码比例 | UTF-8 被误读 |
| 5 | NUL 字节 | UTF-16 被当文本 |
| 6 | 换行符混用 | diff 污染 |
| 7 | **文件体积突变** | **被整文件重写覆盖** |

**退出码**：`0` = 通过，`1` = 有问题（会阻止 commit）。

---

## ⚠️ Windows 用户特别注意

**1. 设置 `PYTHONUTF8=1`（★ 最关键的一步）**

中文 Windows 的 Python 默认用 GBK 读写文件，**这是乱码的头号原因**。

```powershell
[Environment]::SetEnvironmentVariable('PYTHONUTF8', '1', 'User')
```

**设完要重启终端才生效。**

**2. 不要用这些命令**

```powershell
# ❌ PowerShell 5.1 下会写成 UTF-16LE，中文全乱
Get-Content a.md > b.md
echo "text" > file.txt
```

```powershell
# ✅ 正确
Get-Content a.md -Encoding UTF8 | Set-Content b.md -Encoding UTF8
```

**3. 建议升级到 PowerShell 7+**

```powershell
$PSVersionTable.PSVersion.Major   # >= 7 才是 PowerShell Core（默认 UTF-8）
```

**4. `.git/hooks/` 脚本在 Windows 下可能不执行**

建议用 **pre-commit 框架**（跨平台）而不是原生钩子：

```bash
pip install pre-commit
pre-commit install
```

---

## 为什么"提示词里写请勿乱码"没用

| 你以为的原因 | 真实原因 |
|---|---|
| "AI 不小心" | ❌ AI 不是"不小心"，是**机制问题** |
| "没说清楚要求" | ❌ 说清楚也没用，它可能忘了或上下文被截断 |
| "换个更好的模型就行" | ❌ 任何模型都有这两个问题 |

**真实成因**：

| 症状 | 真实成因 |
|---|---|
| **乱码** | 编码不一致（中文 Windows 默认 GBK，工具链某环写成了非 UTF-8） |
| **覆盖** | AI **整文件重写**，而它只看到了文件的一部分（长文件被上下文截断） |
| **无法恢复** | **没有 Git**，出错了只能手工修补 |

**所以防御必须针对这三条，而不是"请求 AI 注意"。**

---

## 一句话总结

> ### Git 是后悔药，UTF-8 是防弹衣，规则是护栏，钩子是最后一关。
>
> **四层都上，AI 才能放心用。**
>
> 如果只能做一件事：**让 AI 改代码之前，先 `git commit`。**

---

## 相关文档

- `AGENTS.md` —— 完整规则（含优先级裁决详解）
- `prompts/` —— 四个场景的提示词模板
- `../07-AI协作安全规范.md` —— 详细的根因分析与原理说明
- `../06-企业级RAG与Prompt规范.md` —— Prompt 工程规范
