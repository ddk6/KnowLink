# RAG 切分方案 V3 改造说明（发给 ClaudeCode）

> 目的：请你基于我当前项目中的切分逻辑，将现有代码从旧版本（V1/V2 风格）升级为 **V3：语义感知切分方案**。  
> 本文档已经把需求、设计原则、核心函数、实现要求、元数据规范、评估方式和注意事项整理好了，请你直接据此修改代码。  
> **重点不是写一份演示代码，而是尽量在我现有项目结构中做增量改造。**

---

## 1. 背景与目标

我们已经验证过：RAG 的检索质量，切分（chunking）是基础设施中的基础。  
同一批保险文档中：

- **V1：固定长度切分** → Recall@5 = **0.67**
- **V2：句子级切分** → Recall@5 = **0.74**
- **V3：语义感知切分** → Recall@5 = **0.91**

这 24 个百分点的提升，不是通过更换 embedding 模型，也不是通过调整检索策略获得的，而是通过 **改进切分方式** 获得的。

### 结论
请你将当前代码的切分模块升级为 **V3 方案**，核心目标如下：

1. **优先保证 chunk 语义完整**
2. **显式利用文档层级结构**
3. **对表格、列表、长章节做专项处理**
4. **保留足够元数据，支持后续检索加权与上下文扩展**
5. **不要只写独立 demo，要尽量融入现有工程结构**

---

## 2. 当前旧方案的问题（需要被替换/修正）

---

### 2.1 V1：固定长度切分的问题

典型逻辑如下：

```python
def chunk_v1(text: str, chunk_size: int = 512, overlap: int = 50) -> list[str]:
    tokens = tokenizer.encode(text)
    chunks = []
    start = 0
    while start < len(tokens):
        end = min(start + chunk_size, len(tokens))
        chunks.append(tokenizer.decode(tokens[start:end]))
        start += chunk_size - overlap
    return chunks
```

#### 主要问题
1. 直接按 token 截断，**会在句子中间断开**
2. 章节标题、前导句、结论句可能被分到不同 chunk
3. 对表格、列表、条款结构完全无感知
4. 生成大量语义残缺 chunk，导致向量表达失真

---

### 2.2 V2：句子级切分的问题

典型思路：按句号、问号、分号、换行等边界切分，再合并到最大长度。

```python
import re

def chunk_v2(text: str, max_size: int = 512, overlap_sentences: int = 2) -> list[str]:
    sentences = re.split(r'(?<=[。！？；\n])', text)
    chunks = []
    current_chunk = []
    current_len = 0

    for sent in sentences:
        sent_len = len(tokenizer.encode(sent))
        if current_len + sent_len > max_size and current_chunk:
            chunks.append("".join(current_chunk))
            current_chunk = current_chunk[-overlap_sentences:]
            current_len = sum(len(tokenizer.encode(s)) for s in current_chunk)

        current_chunk.append(sent)
        current_len += sent_len

    if current_chunk:
        chunks.append("".join(current_chunk))

    return chunks
```

#### 比 V1 好的地方
- 避免了句子中间截断

#### 仍然存在的问题
1. **不理解文档层级**
   - 一级标题、二级标题、正文可能混在一起
2. **不理解特殊结构**
   - 表格、列表、条款项仍然容易被拆坏
3. **列表前导语丢失**
   - 比如：
     - “以下情况不在承保范围之内：”
     - “（1）核辐射”
     - “（2）战争”
   - 如果只召回“（1）核辐射”，没有前导语，模型容易误判语义方向

---

## 3. V3 的目标设计

V3 的核心思想不是“更聪明地按长度切”，而是：

> **先识别结构，再按语义边界切；对超长内容递归细切；对表格和列表做特殊处理；最后保留足够元数据。**

### V3 总体原则
1. **同一章节内的内容尽量放在同一 chunk**
2. **跨章节内容不要混合**
3. **超长章节递归细切**
4. **表格单独处理**
5. **列表与前导句绑定**
6. **重叠（overlap）要保语义边界，而不是机械复制 token**

---

## 4. 你需要实现的 V3 核心能力

---

### 4.1 文档结构识别（多策略融合）

保险文档的标题/编号格式不统一，可能包括：

- `第X章 / 第X条 / 第X节`
- `1. 标题`
- `1.1 小节`
- `（一）`
- `（1）`
- `a)`

请你实现一个通用的 **标题层级识别器**，至少支持如下模式：

```python
import re
from enum import Enum

class HeaderLevel(Enum):
    H1 = 1   # 第X章/第X条
    H2 = 2   # X.X 或 （X）
    H3 = 3   # （1）/a) 等子条款

def detect_header_level(line: str) -> HeaderLevel | None:
    patterns = [
        (HeaderLevel.H1, r'^第[一二三四五六七八九十百\d]+[章条节]'),
        (HeaderLevel.H1, r'^\d+\.\s+[\u4e00-\u9fa5]'),
        (HeaderLevel.H2, r'^\d+\.\d+\s'),
        (HeaderLevel.H2, r'^（[一二三四五六七八九十\d]+）'),
        (HeaderLevel.H3, r'^（\d+）|^\d+）|^[a-z]\)'),
    ]
    for level, pattern in patterns:
        if re.match(pattern, line.strip()):
            return level
    return None
```

#### 实现要求
- 不要只写死一种规则
- 尽量做成 **可扩展规则集**
- 需要能输出层级路径，例如：
  - `第3条 保险责任`
  - `第3条 保险责任 > 3.2 责任免除`
  - `第3条 保险责任 > 3.2 责任免除 > （2）特殊情形`

#### 切分原则
- **同一 section 内优先合并**
- **跨 section 不合并**

---

### 4.2 按 section 切分，并对超长 section 递归细切

在识别出 section 后，不能直接结束，因为很多章节可能很长（例如“保险责任”或“责任免除”）。

请实现类似这样的逻辑：

```python
def split_section(section_text: str,
                  section_path: str,
                  max_size: int = 1024) -> list[dict]:
    tokens = tokenizer.encode(section_text)

    if len(tokens) <= max_size:
        return [{"text": section_text, "section_path": section_path}]

    sub_sections = split_by_sub_headers(section_text)
    if len(sub_sections) > 1:
        result = []
        for sub in sub_sections:
            result.extend(split_section(
                sub["text"],
                f"{section_path} > {sub['title']}",
                max_size
            ))
        return result

    return sentence_aware_split(section_text, section_path, max_size)
```

#### 具体要求
1. 普通 section 默认 `max_size = 1024`
2. 如果是关键条款（如“保险责任”“责任免除”“费率”“赔付”），可设置更大阈值，例如：
   - `max_size = 1536`
3. 对超长 section：
   - 优先按子标题切
   - 如果没有子标题，再退化到句子级切分
4. 句子级切分时：
   - 尽量不要在句子中间断开
   - 要保留 section_path
   - 要支持 overlap

---

### 4.3 表格专项处理

这是必须实现的重点之一。

#### 目标
对于表格，要区分两类：

1. **小表格（≤ 300 token）**
   - 整体作为一个 chunk
2. **大表格（> 300 token 或跨页表格）**
   - 按行切分
   - **每个 chunk 必须重复表头**

请实现类似逻辑：

```python
def split_table(table_text: str,
                table_title: str,
                max_size: int = 300) -> list[dict]:
    rows = parse_table_rows(table_text)
    header_rows = rows[:2]
    data_rows = rows[2:]

    header_text = "\n".join(header_rows)
    header_tokens = len(tokenizer.encode(header_text))

    chunks = []
    current_rows = []
    current_tokens = header_tokens

    for row in data_rows:
        row_tokens = len(tokenizer.encode(row))
        if current_tokens + row_tokens > max_size and current_rows:
            chunk_text = header_text + "\n" + "\n".join(current_rows)
            chunks.append({
                "text": chunk_text,
                "metadata": {
                    "type": "table",
                    "title": table_title,
                }
            })
            current_rows = []
            current_tokens = header_tokens

        current_rows.append(row)
        current_tokens += row_tokens

    if current_rows:
        chunks.append({
            "text": header_text + "\n" + "\n".join(current_rows),
            "metadata": {
                "type": "table",
                "title": table_title,
            }
        })

    return chunks
```

#### 表格处理要求
1. 保证每个 table chunk 都包含表头
2. metadata 里要标明：
   - `chunk_type = "table"`
   - `table_title`
   - `part`（如有分片）
3. 如果当前项目还没有成熟表格解析器：
   - 允许先用较保守方案
   - 但必须保留后续可替换的接口，比如 `parse_table_rows()`

---

### 4.4 列表项处理：前导句必须绑定

请重点修复这种 badcase：

```text
本保险以下情况不在承保范围之内：
（1）核辐射及核污染
（2）战争、军事冲突
（3）被保险人故意行为
```

#### 错误切法
如果把以下内容分别切成独立 chunk：
- `（1）核辐射及核污染`
- `（2）战争、军事冲突`

那么这些 chunk 缺乏上文语义背景，模型可能无法判断这是“免责条款”。

#### 正确做法
1. 识别“前导句 + 列表项”结构
2. 默认将其合并为一个整体 chunk
3. 如果整体过长：
   - 允许继续拆分
   - **但每个拆出来的 chunk 都必须保留前导句**

#### 你需要实现的能力
- 识别前导句（例如以冒号结尾的说明句）
- 识别连续列表项
- 合并时保留顺序
- 拆分时重复前导句

#### 这个逻辑必须单独封装
例如可以设计：
- `detect_list_block(...)`
- `split_list_with_lead(...)`

---

### 4.5 智能 overlap，而不是纯固定 overlap

实验结论：

| overlap 大小 | Recall@5 | 存储增加 |
|---|---:|---:|
| 0 token | 0.81 | 基准 |
| 50 token | 0.86 | +5% |
| 100 token | 0.89 | +10% |
| 200 token | 0.90 | +20% |
| 300 token | 0.90 | +30% |

综合性价比，推荐：
- **基础 overlap = 100 token**

但是不能只做机械 token overlap，还需要：

### 智能 overlap 要求
在确定 overlap 范围后：
1. 尽量向后扩到最近句子边界
2. 避免重叠区域截断半句话
3. 保证 overlap 内容尽量是完整句子/完整语义单元

#### 目标
- 减少“边界处语义断裂”
- 避免 overlap 本身也变成残缺文本

---

## 5. Chunk 元数据设计（必须保留）

请为每个 chunk 保留尽可能完整的 metadata。建议至少包括：

```python
from dataclasses import dataclass

@dataclass
class ChunkMetadata:
    doc_id: str
    chunk_id: str
    section_path: str
    chunk_type: str      # "text" | "table" | "list"
    is_key_clause: bool
    prev_chunk_id: str | None
    next_chunk_id: str | None
    token_count: int
    page_range: str | None
```

### 字段说明
- `doc_id`
  - 原始文档 ID
- `chunk_id`
  - chunk 唯一 ID
- `section_path`
  - 层级路径，支持答案溯源
- `chunk_type`
  - 区分文本、表格、列表
- `is_key_clause`
  - 是否关键条款
- `prev_chunk_id` / `next_chunk_id`
  - 用于后续上下文补全
- `token_count`
  - 便于调试和统计
- `page_range`
  - 如果上游能提供页码信息，则保留

---

## 6. 关键条款识别（建议实现）

为了后续检索加权，请增加 `is_key_clause` 判断逻辑。

### 初步判断策略
可综合以下信息：
1. section 标题关键词匹配
   - `责任`
   - `免责`
   - `费率`
   - `赔付`
   - `保险责任`
   - `责任免除`
2. chunk 文本中高频关键词匹配
3. 标题优先级大于正文关键词

### 目标
- 能较稳定地识别关键条款 chunk
- 输出给 metadata
- 后续检索层可做额外加权（如果当前工程支持）

---

## 7. 推荐的模块化改造方式

请不要把所有逻辑堆在一个函数里。  
建议尽量拆成以下模块/函数（名字可按项目风格调整）：

```python
detect_header_level(line)
split_document_by_sections(text)
split_section(section_text, section_path, max_size)
sentence_aware_split(text, section_path, max_size, overlap)
detect_list_block(lines)
split_list_with_lead(lead_sentence, list_items, max_size)
split_table(table_text, table_title, max_size)
build_chunk_metadata(...)
link_neighbor_chunks(chunks)
is_key_clause(section_path, text)
```

### 工程要求
1. 代码要尽量能插入我现有项目，而不是另起炉灶
2. 尽量复用已有 tokenizer / document parser / metadata 结构
3. 如果现有接口不兼容，请同步修改调用链
4. 给出必要的注释，但不要写成教学 demo 风格

---

## 8. 建议采用的整体处理流程

请尽量将主流程整理为类似下面的 pipeline：

```python
raw document
   ↓
结构识别（标题 / 表格 / 列表）
   ↓
按 section 粗切
   ↓
超长 section 递归细切
   ↓
表格专项切分
   ↓
列表专项切分
   ↓
句子级兜底切分
   ↓
智能 overlap
   ↓
补齐 metadata
   ↓
串联 prev / next chunk
   ↓
输出最终 chunks
```

---

## 9. 你修改代码时必须注意的坑

### 坑 1：chunk_size 不能过小
以前使用 `512 token` 在保险文档中容易导致语义不完整。  
建议：
- 普通内容：`1024`
- 关键条款：`1536`

### 坑 2：表格 chunk 必须带表头
否则 LLM 无法理解字段意义，容易答错。

### 坑 3：列表项不能脱离前导句
否则“免责条款”会被误当成“保障内容”。

### 坑 4：不要只做规则识别，不做兜底
保险文档格式复杂，不可能完全规则化。  
必须设计：
- 标题规则识别
- 失败时兜底句子切分
- 特殊结构识别失败时的保守策略

---

## 10. 评估与验证要求

你在改完代码后，请尽量给出以下内容：

### 10.1 单元测试 / 样例测试
至少覆盖：
1. 普通章节切分
2. 超长章节递归切分
3. 表格切分（小表格 / 大表格）
4. 列表前导句绑定
5. overlap 边界完整性
6. section_path 是否正确
7. prev/next chunk 是否串联正确

### 10.2 输出示例
请给出至少 2~3 个输入输出示例，展示：
- 原始文本
- 切分结果
- metadata 示例

### 10.3 若项目中已有评估接口
如果我现有代码里已经有检索评估流程，请顺便接入并尽量保证后续可以验证：
- Recall@5
- 表格类问题召回
- 列表类问题召回
- 长章节类问题召回

---

## 11. 交付要求（ClaudeCode 需要完成什么）

请你最终输出的内容至少包括：

1. **修改后的核心代码**
2. **涉及到的文件改动说明**
3. **如果改了接口，说明调用方式怎么变**
4. **必要的测试样例**
5. **如果你发现我现有项目结构不适合实现 V3，请直接指出具体问题并顺手重构**
6. **优先给出可运行版本，不要只给思路**

---

## 12. 我对实现质量的具体要求

请按下面的优先级执行：

### 第一优先级
保证 chunk 的语义完整性，尤其是：
- 章节边界
- 表格表头
- 列表前导句
- 长条款上下文

### 第二优先级
让代码能真正接入现有项目，而不是单独写一个新脚本

### 第三优先级
尽量保持良好的可维护性和可扩展性

---

## 13. 可以接受的折中方案

如果我的现有工程里暂时缺少以下能力：
- 精确表格解析
- 稳定页码映射
- 完整文档结构树

那么你可以采用 **可渐进优化** 的实现方式：

1. 先把接口抽象好
2. 先用保守策略实现
3. 在注释里明确后续可替换点

但是：
- **不能因为暂时做不到完美，就退回 V1/V2 的简单切法**
- 即使是保守实现，也必须体现 V3 的核心思想

---

## 14. 最后总结：你需要落实的 V3 核心改造点

请确保最终代码真正实现以下几点：

- [ ] 基于标题/编号的层级结构识别
- [ ] 按 section 切分，而不是纯 token 切分
- [ ] 超长 section 的递归细切
- [ ] 表格的专项切分与表头复制
- [ ] 列表项与前导句绑定
- [ ] 智能 overlap（尽量按句子边界）
- [ ] 完整 metadata
- [ ] prev/next chunk 串联
- [ ] 关键条款识别
- [ ] 能接入我当前工程，而不是独立 demo

---

## 15. 给 ClaudeCode 的直接执行指令

请你现在开始做以下事情：

1. 先阅读我当前项目中与文档切分、chunk 构建、metadata、向量入库相关的代码
2. 找出当前实现中属于 V1 / V2 风格的部分
3. 按本文档的 V3 要求直接修改
4. 尽量少讲空泛思路，优先输出：
   - 改了哪些文件
   - 每个文件改了什么
   - 关键函数的新实现
   - 如何运行测试
5. 如果我当前代码结构存在阻碍，请直接重构成更合理的形式
6. 最终给我一份**可落地、尽量可运行、尽量少留坑**的修改结果

---

## 16. 补充说明（这部分是背景，不是硬性实现格式）

本次需求来自一个保险文档 RAG 项目的切分方案演进总结。  
已知经验如下：

- 固定长度切分对结构化文档伤害很大
- 句子级切分只能解决“半句截断”，解决不了层级和特殊结构问题
- 真正有效的方案必须面向：
  - 章节结构
  - 长章节递归
  - 表格
  - 列表
  - overlap 边界完整性
  - metadata 支持后续检索增强

请优先保证这些核心设计被落实到代码里，而不是只停留在说明层面。
