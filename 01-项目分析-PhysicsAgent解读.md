# 01 · 被分析项目全面解读：PhysicsAgent

## 对知序的参考边界（2026-10-17）

本文是外部参考工程的历史分析，本轮未重新审计其代码、服务条款、模型型号或价格。**它不定义知序的产品需求、当前技术栈或已实现能力。** 当前基线见 [02需求](02-需求分析.md)、[03架构](03-架构设计.md) 及 [04实施路线](04-实施指南.md)。

知序以企业知识库与业务问答为主，必须建设授权文档入库、持久索引、版本与权限治理；下文“你不需要本地知识库”“同款LangGraph/Python”“换SQLite”等属于旧场景，不能套用当前Java工程。

可借鉴显式数据契约、有限循环、工具隔离、阶段进度和证据定位。不可照搬证据多就默认充足、评估失败即能回答、异常吞掉变空资料、未经许可外发企业内容，或向用户展示模型内部推理。进度展示业务阶段和可审计依据即可。Harness在知序中指工具白名单、权限/参数校验、预算/时限、必要确认、审计与评估约束，不表示已接入某个同名框架。

下方“当前”“已修复”“近乎完美”等措辞仅属于原分析时点；旧价格、条款和成本比例不能当采购或上线依据。

<details>
<summary>PhysicsAgent历史分析原文（仅作设计参考）</summary>

> 分析对象：当前文件夹下的 `physics-agent/`
> 分析方式：通读源码（图结构、10 个节点、MCP 工具层、SSE 编排层、前端）、配置文件、文档
> 一句话结论：**这是一个"面向物理学前沿问题的多 Agent 科研协作状态机"，本质是"Self-RAG + 多源交叉验证 + 事实/风格双轨渲染"的完整工程实现，工程完成度远高于一般练手项目，非常适合作为你自建 RAG 系统的参考骨架。**

---

## 一、这个项目到底在做什么（作用）

### 1.1 用户视角

用户打开网页，输入一个前沿物理问题，比如：

> "如何从黑洞视界附近的量子场论推导出纠缠熵的面积定律？"

系统不会直接让大模型回答，而是先做一整套"研究工作"，最后给出带引用、可追溯的答案，并且支持两种口吻：

- **严谨报告模式（scientific）**：保留 LaTeX 公式、实验数据、误差范围、`[^n]` 引用锚点，写给科研人员看
- **科普讲解模式（popular）**：把同样的物理事实重新组织成小白能懂的叙述，带生活化类比，但**数字一个都不许改**

同时前端会实时展示"当前进行到哪一步"（意图分析 → 规划 → 检索 → 评估 → 交叉验证 → 知识综合 → 渲染）。

### 1.2 系统视角（核心链路）

```
START → intent ─┬─(需证据)→ planner → worker×(并行) → retriever×(并行) → evaluator
                │                                                          ├─(证据不足,≤3轮)→ retriever 重查
                │                                                          ├─(证据过多)→ rag×(并行) → rag_merge
                │                                                          └─(默认)→ cross_validator
                │                                                                          ↓
                └─(闲聊/通用)──────────────────────────────────────────────────────→ render → END
                                                                        synthesizer ──→ render
```

关键点：**它不是一条直线，而是一个带条件回边的状态机**。评估节点发现证据不足会**回退到检索节点**（最多 3 轮），这是 Self-RAG 的典型形态。

---

## 二、技术栈

| 层次 | 技术选型 | 说明 |
|---|---|---|
| 语言/运行时 | Python ≥ 3.13 | 用到了较新的类型语法 |
| 包管理 | **uv** | 依赖锁在 `uv.lock`，比 pip/poetry 快很多 |
| Agent 编排 | **LangGraph** | 核心。用 `StateGraph` + `Send` 做动态并行 |
| 状态定义 | `TypedDict` + `Annotated` reducer | 并行聚合用 `operator.add`，计数器用 `max` |
| 数据校验 | **Pydantic v2** | 结构化输出的 schema 约束，含 `field_validator`/`model_validator` |
| LLM 接入 | `langchain-openai` | **全部走 OpenAI 兼容协议**，所以能混用多家 |
| 模型调用方式 | `with_structured_output()` | 强制模型输出符合 Pydantic 模型的 JSON |
| Web 框架 | **FastAPI** + uvicorn | 异步、`StreamingResponse` |
| 流式通信 | **SSE**（`text/event-stream`） | 自定义事件协议，7 种事件类型 |
| 工具层标准 | **MCP（FastMCP）** | 检索工具独立成 Server，stdio 子进程通信 |
| MCP 客户端 | 官方 `mcp` SDK | 没用 `langchain-mcp-adapters`，直接用 `ClientSession` 走最小依赖路径 |
| 学术检索 | `arxiv` 库 4.x | 走 arXiv 官方 API |
| 网页检索 | `langchain-tavily` | Tavily API |
| Embedding | DashScope OpenAI 兼容接口 | 模型 `qwen3.7-text-embedding` |
| 向量计算 | **numpy**（不用向量数据库） | 内存里算余弦相似度 |
| 可观测性 | **LangSmith** | 环境变量必须在 import langchain 之前设置 |
| 会话存储 | 本地 JSON 文件 | `data/sessions/<id>.json`，带分级压缩 |
| 前端 | 原生 HTML/CSS/JS | 无框架、无构建步骤，单文件 `app.js` 约 1400 行 |
| 测试 | pytest | 61 个用例，但多数会真实调用 API 产生费用 |

### 值得注意的依赖细节

- **没有向量数据库、没有 LangChain 的 Retriever 抽象、没有 `langchain-community`**。这是刻意的精简。
- **没有 checkpointer**（不用 `MemorySaver`）：每轮对话都是全新的 State，历史靠文件装载。这样 State 不会随轮次无限膨胀。
- `pyproject.toml` 里其实装了 `langchain-mcp-adapters`，但代码里没用，走的是官方 SDK。

---

## 三、设计思想（这是最值得你学的部分）

我把它的设计思想拆成 8 条，按重要性排序。

### 思想 1：状态机 + 动态并行，而不是"一条 Prompt 打天下"

单靠一个超长 Prompt 让模型"先检索再评估再回答"，问题是：**流程不可控、无法重试、无法并行、无法观测**。

它的做法是把"研究工作"显式拆成节点，节点之间只通过 State 通信。带来三个好处：

1. **可重试**：评估节点判不足 → 回退检索。这在单 Prompt 里做不到。
2. **可并行**：多个子问题同时检索（`Send` 机制），端到端延迟显著下降。
3. **可观测**：每个节点的输入输出都能在 LangSmith 里看到，出问题能定位到具体节点。

> **对你的启示**：RAG 系统的质量瓶颈通常不在"生成"，而在"检索"和"评估"。把这两步显式化成节点，你才有地方做优化。

### 思想 2：意图压缩代理 —— `summary_instruction` 替代原始 query

`planner` 节点产出一个 `summary_instruction`，它把**历史对话 + 当前提问 + 图片意图**压缩成一句话。**从这个节点之后，下游所有节点都不再读原始 query**，只读这个压缩代理。

为什么这么设计？

- 多轮对话里，"它呢？""那这个呢？"这类指代必须靠历史才能解析
- 如果每个下游节点都自己去翻历史，Prompt 会重复膨胀，且各个节点的理解可能不一致
- 压缩一次，下游统一口径，**意图理解的一致性由架构保证**

作者的注释写得很明确：

> "自此节点之后，query 原始提问不再直接传入下游，所有检索、验证、知识综合节点均以本 Plan 的 summary_instruction 与 sub_questions 为唯一意图来源。"

> **对你的启示**：多轮 RAG 里，"查询改写（Query Rewriting）"不是可选项。你要么在 planner 里做，要么单独做一个节点，但一定要有一个**唯一的意图来源**。

### 思想 3：零成本重查 —— 预生成 Fallback 查询队列

这是我觉得最巧妙的一处设计。

常规做法：证据不足 → 让模型重新生成查询 → 再检索。**每轮重查都要额外调用一次 LLM**。

它的做法：在 `worker` 节点生成查询配置时，**强制预先生成 2 个 fallback 查询**，用类型系统卡死：

```python
fallbacks: Tuple[QueryConfig, QueryConfig] = Field(...)
# 类型层面强制恰好 2 个，JSON Schema 生成 minItems=maxItems=2，LLM 不易出错
```

然后重查时按 `loop_count` 直接取用：

```python
loop=1 → primary
loop=2 → fallbacks[0]
loop=3 → fallbacks[1]
```

**单轮重查的 LLM 调用成本为零。**

而且 fallback 有明确的退化策略：
- `fallback[0]`：放宽条件 / 同义词替换
- `fallback[1]`：泛化角度 / 切换关键词

还配了 `search_depth` 的联动：首轮 `advanced`（更准），重查轮 `basic`（更广）。

> **对你的启示**：这是"用架构设计换成本"的典范。你能预判的失败路径，就提前把补救方案生成好。

### 思想 4：评估节点的"默认充足"倾向 —— 反直觉但正确

`evaluator` 的 System Prompt 里有一段非常反直觉的话：

> "你判'不足'的子问题会被送回检索器重新检索。但重查使用的是第 2、3 轮备用查询词，它们比首轮查询更宽泛、更不精确，通常只能召回相关性更弱、噪声更大的结果——对最终回答的提升非常有限，还会显著拖慢流程。
> 因此你的默认立场必须是'充足'。"

这背后是对 RAG 的一个深刻理解：**重查的边际收益递减，而成本（延迟 + Token）线性上升。**

它还做了三层防御：
1. **预确认充足**：单子问题证据 ≥ 12 条，直接跳过 LLM 评估，置信度强制 1.0（省一次 LLM 调用，也避免 Prompt 爆炸）
2. **置信度门槛**：判"不足"但置信度 < 0.5 的，不触发重查（防误报）
3. **循环上限**：`MAX_LOOP_COUNT = 3` 硬拦截，防死循环

还有完整的**降级路径**：LLM 调用失败 → 降级为数量规则（双源全空判不足）。

> **对你的启示**：评估节点最容易写成一个"永远说不够"的挑剔鬼，导致系统慢且贵。要给它明确的"什么时候该停"的规则。

### 思想 5：RAG 不做向量库，只做实时精排 —— "轻量级 RAG"

这里的 RAG 用得很有意思。它**没有**做经典的"文档切片 → 向量化 → 存向量库 → 检索"。

它做的是：

```
检索返回的原始证据（几十条）
   ↓
用 rag_fusion_query（一个精炼的完整陈述句）做 query
   ↓
调 Embedding API，把 query + 所有证据 content 一起编码
   ↓
numpy 算余弦相似度（归一化后点积）
   ↓
取 Top-2
```

**没有向量库、没有持久化、没有分片，零维护成本。**

代码里 `RAG_LIMIT = 2`，注释写着 `3->2`，说明是调优过的。

而且触发时机很讲究：**不是每次都 RAG，而是评估节点判定"证据多但噪声大"时才触发**。判断依据写在 Prompt 里：

> "证据多但噪声大/重复时，正确做法是 RAG 精排（不重新检索、成本低），而不是判不足重查。"

> **对你的启示**：这是"RAG 不等于向量数据库"的最好例子。当你的语料是**每次实时检索来的、一次性的**，用 Embedding 实时精排就够了，不需要向量库。**向量库的价值在于"跨会话持久化 + 大规模召回"，不是 RAG 的必要条件。**
>
> ✅ **你现在决定不做本地知识库，恰好走的就是这条路**——
> 你的语料（Tavily/arXiv 的搜索结果）每次都是新的、一次性的，
> **所以整个向量库、切片、Embedding 流水线你都可以不做。**
>
> 💡 **但那个"精排"的思想仍然值得借鉴**：
> 当你的多查询扩展召回了 20+ 条结果时，
> 可以用**千问的 Rerank API**（¥0.5/百万 tokens）替代它的 numpy 余弦相似度，做同样的"精排到 Top-5"。
> 这是可选的第二版优化（见 [03-架构设计.md](03-架构设计.md) 的 ADR-3）。

### 思想 6：交叉验证节点 —— 把幻觉风险变成显式约束

`cross_validator` 节点的职责很窄：**只做逻辑与证据质量审查，不回答用户问题**。Prompt 里有一串"禁止事项"：

- 禁止基于自身知识库补充任何未在证据中出现的物理事实
- 禁止对规划节点的子问题方案进行"优化"或"重新规划"
- 禁止生成面向用户的最终回答
- 禁止在没有明确冲突证据的情况下，臆造冲突
- 禁止输出 JSON、Markdown 表格（只输出纯文本段落）

它给出**置信度加权规则**：

| 维度 | 规则 |
|---|---|
| 来源权威性 | **arXiv > Tavily**（预印本 > 实时网页） |
| 时间新鲜度 | 同一来源内，越新越好 |
| 数据类型 | 实验数据/同行评审 > 理论推导 > 科普解读 > 论坛讨论 |

> **对你的启示**：多源检索一定会遇到冲突。你需要一个**明确的裁决规则**，而不是让最后的生成模型自由发挥。规则写死在 Prompt 里，行为才可预测。

### 思想 7："事实-风格"双轨架构 —— 事实中间层

这是我觉得最有架构品味的设计。

问题：如果要同时支持"严谨报告"和"科普讲解"两种风格，最笨的做法是**跑两遍全流程**（检索两次、综合两次），成本翻倍。

它的做法是加一个中间层：

```
检索证据 → synthesizer（知识综合）→ knowledge_synthesis（结构化事实文档）
                                          ↓
                              render 节点（唯一接触用户的地方）
                                    ↙        ↘
                          scientific 插件   popular 插件
```

`synthesizer` 产出一份**风格无关的物理事实文档**（含 LaTeX、实验数据、`[^n]` 引用锚点），然后 `render` 节点根据用户选的模式，**动态拼接不同的风格插件 Prompt**：

```python
if render_mode == "popular":
    system_content = SKELETON + POPULAR_MODE_PLUGIN
else:
    system_content = SKELETON + SCIENTIFIC_MODE_PLUGIN
```

三种插件：`GENERAL_MODE_PLUGIN`（闲聊）/ `SCIENTIFIC_MODE_PLUGIN` / `POPULAR_MODE_PLUGIN`。

**事实数据共享，风格即插即用，零偏差。**

而且它强制约束了事实完整性：

> "事实零妥协：物理知识梳理中的所有物理结论、实验数据、公式结果、数值严禁改变。你可以换说法，但不能换数字。"

> **对你的启示**：任何"同一个知识、多种输出形态"的需求（严谨/通俗、中文/英文、长/短），都应该抽一个**事实中间层**，而不是重复跑流程。这个模式可以复用性极强。

### 思想 8：MCP 工具层解耦 —— 编排与工具彻底分离

检索能力被封装成独立的 MCP Server（`retriever_server.py`），通过 **stdio 子进程**与主进程通信。

```python
# 客户端
StdioServerParameters(
    command=sys.executable,        # 保证子进程用同一个 .venv 解释器
    args=["-m", "src.mcp.retriever_server"],
    cwd=str(PROJECT_ROOT),         # 必须指向项目根，否则 -m 找不到 src 包
)
```

生命周期：**每次进入 `async with` 都拉起一个全新子进程，退出即销毁。**并行的 retriever 实例各自持有独立连接，互不干扰。

代码里有一条注释点出了 stdio 模式的铁律：

> "stdio 模式下 **stdout 只能写协议消息**，所有日志必须走 stderr，否则客户端会把日志当成协议帧解析，直接崩溃。"

另外，`call_tool` 的返回值解析做了三层兜底：`structuredContent` → 解包 `{"result": ...}` → `TextContent` 里的 JSON 字符串。这是**对不同 MCP 服务器实现的兼容性处理**，很实用。

> **对你的启示**：MCP 的价值在于"工具可以独立开发、独立测试、独立替换"。你的检索源（Tavily / arXiv / 备用搜索）都可以做成独立的 MCP Server。要加新工具，只写一个新 Server，不动编排层。
>
> 但也要注意代价：**每个并行实例拉起一个 Python 子进程**，启动开销不小。这是这个设计目前的性能隐患（见第五节）。

---

## 四、工程细节中的亮点（可直接借鉴的招式）

### 4.1 SSE 事件协议的精细设计

7 种事件：`start` / `node` / `progress` / `thinking` / `output` / `token` / `done` / `error`

最妙的一处：**并行节点的 token 流被刻意丢弃**。

```python
PARALLEL_NODES = {"worker", "retriever", "rag"}
...
if not node or node in PARALLEL_NODES:
    continue  # 无节点归属 / 并行节点只报进度，不转 token
```

原因注释写得很清楚：**多实例并行时 token 会交叉混杂，读起来是乱码**。所以并行节点只推 `progress` 事件（"子问题2 检索到 5 条"）。

而串行节点则区分：
- 有 `reasoning_content` → `thinking` 事件（推理链）
- 是 `render` 节点 → `token` 事件（最终回答，逐字显示）
- 其他串行节点 → `output` 事件（中间结果）

**这是"什么该给用户看、什么不该给用户看"的成熟判断。**

### 4.2 图执行用一次 `astream` 拿齐三类事件

```python
async for mode, payload in graph.astream(
    initial_state,
    stream_mode=["tasks", "messages", "custom"],   # 三种流同时订阅
    config={...},
):
```

- `tasks` → 节点开始/结束
- `messages` → LLM token
- `custom` → 节点内手动推送的进度

对应节点内的 `emit_progress()`：

```python
def emit_progress(node: str, text: str) -> None:
    try:
        get_stream_writer()({"node": node, "text": text})
    except Exception:
        pass   # 非流式上下文（单元测试）下静默忽略
```

**这个 `try/except pass` 很关键**：让同一个节点函数既能跑在流式服务里，也能被单元测试直接调用。

### 4.3 用类型系统给 LLM 上"紧箍咒"

`schemas.py` 里的校验器设计值得学：

```python
fallbacks: Tuple[QueryConfig, QueryConfig]  # 恰好 2 个，schema 层面卡死

@model_validator(mode="after")
def _primary_differs_from_fallbacks(self):
    # 禁止 fallback 与 primary 重复
```

还有 `rag_fusion_query` 的"关键词堆砌"检测：

```python
tokens = v.split()
short = [t for t in tokens if len(t) <= 3]
if len(tokens) > 5 and len(short) / len(tokens) > 0.95:
    raise ValueError("疑似关键词堆砌，请写成完整陈述句")
```

**用代码约束 LLM，比在 Prompt 里写"请不要 XXX"有效得多。**

### 4.4 校验失败反馈重试

`worker` 节点的重试逻辑：

```python
for attempt in range(max_retries):
    try:
        result = structured_model.invoke(messages)
        break
    except ValidationError as e:
        if attempt == max_retries - 1:
            return {... "query_config": None, "error": str(e) ...}
        # 把 ValidationError 翻译成 LLM 能懂的诊断信息，再试一次
        messages = [system_prompt, user_message,
                    HumanMessage(content=_build_retry_feedback(e.errors()))]
```

而 `_build_retry_feedback` 会把技术性的错误翻译成人话：

```python
if "工具名" in msg or "snake_case" in msg:
    feedback_parts.append("字段 [x]：tool_queries 的键名必须是 'arxiv' 或 'tavily'...")
```

**"把校验错误翻译成模型能理解的反馈再重试"是一个非常有用的模式。**

### 4.5 历史压缩的分级策略

```python
MAX_HISTORY_TURNS = 10   # 达到 10 轮触发压缩
KEEP_RECENT_TURNS = 4    # 保留最近 4 轮
```

压缩时分两级：
- **久远部分** → **重度压缩**（≤150 字，只留核心主题和关键结论）
- **最近 4 轮** → **轻度压缩**（每轮 1-2 句，保留关键数据）

压缩后固定为 2 条 summary 记录。**这个"近详远略"的分级压缩思路很实用。**

### 4.6 安全细节

- `session_id` 正则清洗防路径穿越：`re.sub(r"[^a-zA-Z0-9_-]", "", session_id)`
- `.gitignore` 已经把 `config/agent.yml`（真实密钥）排除了
- 所有外部调用都有 `try/except` 兜底返回空结果
- LangSmith 环境变量**必须在 import langchain 之前**设置（tracer 在模块加载时初始化）

---

## 五、这个项目的不足与坑（你自建时要注意）

我在读代码时发现了这些问题，对你很有参考价值。

### 5.1 已修复的坑（代码里能看到痕迹，说明踩过）

| 坑 | 现象 | 修复方式 |
|---|---|---|
| `evidence_curated` 键类型 | LangGraph checkpoint 序列化时 `int → str` | 统一用 `str(k)` 作键，注释明确标注 |
| 布尔值当整数 | `True/False` 是 `int` 子类，被当成 `1/0` | `isinstance(i, bool)` 先排除 |
| 循环死锁 | evaluator → retriever 可能无限循环 | `MAX_LOOP_COUNT = 3` 硬拦截 |
| Prompt 爆炸 | 证据太多导致截断 | 预确认充足（≥12 条）→ 只截 100 字 |
| 类型校验误伤 | 中文场景下"关键词堆砌"检测误报 | 中文场景放宽到 0.95 |

### 5.2 尚未解决的问题

**① 前端文件不同步（文档与实际不符）**

`AGENTS.md` 里写"前端已整体删除待重建"，但 `frontend/index.html` 和 `frontend/js/app.js`（1396 行）是存在的。而 `docs/architecture.md` 又说 `graph_viz.js / renderers.js / sse.js 为空`。实际是 `app.js` 独扛全部逻辑，约 1400 行单一文件——**可维护性差**。

**② 并行策略粗放，成本失控风险**

- `worker` 节点是**每个子问题一个 LLM 调用**，2-7 个子问题就是 2-7 次调用
- `retriever` 是**每个子问题一个 MCP 子进程**，每个子进程还要拉起一次 Python 解释器

子问题多的时候，一次提问的 LLM 调用可能达到 **10+ 次**。这对"每天上千次提问"的场景是明显的成本压力。

**②.5 ⚠️ 并行打 arXiv 可能违反官方使用条款（这是一个合规问题）**

这一点我在读代码时没注意，是后来查 arXiv 官方[使用条款](https://info.arxiv.org/help/api/tou.html)才发现的：

| 约束 | 内容 |
|---|---|
| 速率 | **每 3 秒最多 1 个请求** |
| 并发 | **同时只允许 1 个连接** |
| 绕过 | 限制适用于**你控制的所有机器整体**，**不得增加机器绕过** |

而这个项目用 LangGraph 的 `Send` 机制**并行**发起 arXiv 检索（`route_after_worker` 为每个子问题分发一个 retriever 实例），
每个实例各自通过 MCP 调用 `search_arxiv`。

```python
# builder.py 的 route_after_worker：为每个 worker 结果分发一个 retriever
for wr in worker_results:
    sends.append(Send("retriever", {...}))
# 这些 retriever 会并发执行，每个都可能打一次 arXiv —— 直接违反 1 连接/3 秒 的限制
```

**后果**：在实际部署中，arXiv 会限流甚至封 IP，检索功能静默失效（因为代码用 `try/except` 吞掉了异常，只返回空列表）。

> **对你的启示（很重要）**：
> 1. **并行检索不是无条件的优点**——取决于数据源的限速策略
> 2. **Tavily 可以并行，arXiv 必须串行**，两者要分开调度
> 3. 这也说明 PhysicsAgent 的"多子问题并行检索"设计**看似优雅，实际有合规隐患**
> 4. 如果你要用 arXiv，**必须做全局单例限速器 + 串行队列 + 结果缓存**

**③ 没有真正的检索质量评估**

系统评估的是"证据够不够"（数量/相关性），但**没有评估"检索结果是否真的回答了子问题"**。这是 RAG 里最常见的失败模式（检索到了相关的但没用的东西）。

**④ 没有测试数据与评估集**

测试是"节点级"的、且真的调 API。**没有端到端的质量评估**（比如：给定 50 个问题，答案正确率多少）。这意味着**你无法知道某次改动是变好了还是变坏了**。

**⑤ 会话存储用本地 JSON 文件**

- 多进程/多副本部署时会冲突（`asyncio.Lock` 只在单进程内有效）
- 没有过期清理机制
- 无法扩展成分布式

"公开小规模上线"场景下，这是**必须替换**的一环。

**⑥ CORS 全开 + 无限流**

```python
allow_origins=["*"]
```

代码注释自己也写了"对外部署前需收紧"。而且**没有任何限流**，一个脚本就能把你的 API 额度刷爆。

**⑦ 没有本地文档库**

它完全没有本地语料，知识全部来自实时检索。

> ✅ **好消息**：**你的系统也不需要本地库**（你已决定不做）。
> 所以在"知识来源"这一点上，**你俩完全一致**——
> 这意味着它的**检索层设计（MCP 封装、搜索调度、证据评估、SSE 协议）对你参考价值极高**，
> 你可以直接借鉴。
>
> ⚠️ **但有一处必须改**：它的**并行 arXiv 检索违反官方条款**（见 5.2 ②.5）。

---

## 六、和你需求的对照（你缺什么、它有什么）

| 能力 | PhysicsAgent | 你的系统 | 你要做的 |
|---|---|---|---|
| 多 Agent 编排 | ✅ LangGraph | ✅ 同款 | 复用思路，**从 10 节点砍到 5 节点** |
| 外部检索 | ✅ arXiv + Tavily | ✅ 同款 | **直接复用**，但 arXiv 要改串行（见 5.2②.5） |
| 本地文档库 | ❌ 没有 | ❌ **也不需要** | ✅ **无差异** |
| 证据评估 | ✅ 很成熟 | 需要 | 大幅简化 |
| 交叉验证 | ✅ | 可选 | 可简化或砍掉 |
| 双风格渲染 | ✅ 事实/风格双轨 | 可选 | 值得借鉴 |
| 轻量精排 RAG | ✅ numpy 实时精排 | 可选 | 可借鉴思路（用 Rerank API 替代） |
| SSE 流式 | ✅ 7 种事件 | 需要 | **复用协议设计** |
| MCP 工具层 | ✅ | ✅ 同款 | **复用，但改长驻连接池** |
| LangSmith | ✅ | ✅ 同款 | 改用 Langfuse 免费档 |
| 会话持久化 | ⚠️ 本地 JSON | 需要 | **换成 SQLite/PG** |
| 限流/鉴权 | ❌ | 需要 | **新增** |
| 质量评估集 | ❌ | 需要 | **新增** |
| 成本追踪 | ❌ 完全没有 | 需要 | **新增**（搜索额度有限，必须监控） |
| 搜索缓存 | ❌ 没有 | 需要 | **新增**（★ 省钱主力） |

**结论：在"外部检索 + 多 Agent 编排"这块，它是近乎完美的模板。你要做的是「简化的同时补上工程化」。**

---

## 七、给你的三条核心结论

1. **它的骨架值得抄，它的复杂度不必抄。**
   10 个节点对你的需求来说太多了。你可以砍到 5-6 个（意图→改写→检索→精排→生成），把交叉验证、双源锚点这些"物理学特化"的东西去掉。
   成本上，这个简化能省到 **1/3**（详见 [05-预算分析.md](05-预算分析.md)）。

2. **它最大的技术贡献是"用架构换成本"这个思路。**
   预生成 fallback 队列、预确认充足跳过评估、只对噪声大的证据做精排——这三个设计都指向同一个目标：**在保证质量的前提下，减少 LLM 调用次数**。这是你自建时最该学的思维。

3. **它最大的缺口是"工程化"，不是"能力"。**
   它没有任何限流、没有搜索缓存、没有成本追踪、没有评估集——
   **这四样恰恰是你公开上线必须有的**。能力上它已经够强了，你要补的是**上线所需的工程护栏**。

> ⚠️ **还有一个必须提醒你的隐患**：它的**并行 arXiv 检索违反官方使用条款**（1 连接 / 3 秒，禁止多机绕过），
> 且异常被 `try/except` 静默吞掉，所以**问题不会报错，只会静默返回空结果**。
> 你自建时如果照抄并行设计，会遇到同样的问题。详见 5.2 ②.5 节。

---

> 下一步：阅读 [02-需求分析.md](02-需求分析.md)，我基于你的回答（本地 Markdown 笔记库 + 公开小规模上线 + 国内 API 为主 + 国内云服务器）整理了完整需求。

</details>
