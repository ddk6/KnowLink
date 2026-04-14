
# MinerU 返回 JSON 结构调研手册（给 CC）

> 目的：给做 JSON 解析 / chunk 切分的人一个可直接查阅的说明，避免“明明有解析结果，但最后得到 0 个 chunk”的情况。  
> 结论先说：**不要把 `_model.json` 当正文来源来切 chunk；正文抽取与按页分组，优先看 `_content_list.json`（或 3.0+ 的 `_content_list_v2.json`）；需要深度调试再看 `_middle.json`。**

---

## 1. 这份手册基于什么

这份手册基于 MinerU 官方输出文件说明，以及 2026 年初官方仓库里关于分页抽取、`content_list.json` 与 `_model.json` 区别的讨论整理而成。

重点依据：

- 官方输出文件文档：`output_files`
- 官方 changelog（确认 `content_list_v2.json`、表格合并等版本变化）
- 官方仓库 issue / discussion（确认真实使用中最常见的踩坑点）

---

## 2. 最重要的结论（先看这个）

### 2.1 真正适合做 chunk 的文件

按推荐顺序：

1. **`*_content_list_v2.json`（3.0+）**  
   新结构，按页分组，统一成 `type + content`，更适合新项目做结构化消费。
2. **`*_content_list.json`（legacy 但仍常用）**  
   扁平、按阅读顺序排列，最适合快速做 chunk。
3. **`*_middle.json`**  
   结构最完整，适合调试、二次开发、定位“哪一层丢内容了”。
4. **`*_model.json`**  
   **不适合直接做文本 chunk。**

### 2.2 为什么很多人会切出 0 个 chunk

最常见原因有 5 个：

1. **读错文件了**：去解析 `_model.json`，但它主要是布局/检测结果，不是正文抽取结果。  
2. **字段名读错了**：正文在 `text`、`code_body`、`table_body`、`list_items` 等字段里，不是所有块都有 `text`。  
3. **把辅助块全过滤掉后，正文也一起被过滤掉了**：例如错误地按 `type != "text"` 全删。  
4. **没按 `page_idx` / 页面层级处理**：尤其是分页聚合逻辑写错。  
5. **跨页表格默认被合并**：如果你按“每页必须有一个表格块”来切，后续页可能看起来像“空的”。

### 2.3 一句话建议

- **想稳定产出 chunk：优先用 `content_list.json` / `content_list_v2.json`**
- **想排查为什么没切出来：拿 `middle.json` + `layout.pdf` + `span.pdf` 一起看**
- **不要用 `_model.json` 直接抽正文**

---

## 3. MinerU 常见输出文件总览

| 文件 | 作用 | 适不适合做 chunk | 备注 |
|---|---|---:|---|
| `*.md` | 最终 Markdown 文本 | 可用，但不保留完整结构 | 快速预览用 |
| `*_content_list.json` | 简化后的阅读顺序内容列表 | **最推荐** | 适合 chunk / RAG |
| `*_content_list_v2.json` | 3.0+ 新结构输出 | **推荐** | 按页分组，统一 schema |
| `*_middle.json` | 完整中间结构 | 有条件可用 | 适合 debug / 二次开发 |
| `*_model.json` | 模型原始检测结果 | **不推荐** | 很容易导致 0 chunk |
| `*_layout.pdf` | 版面/阅读顺序可视化 | 不做 chunk | 看布局是否对 |
| `*_span.pdf` | span 级可视化（pipeline） | 不做 chunk | 看文字/公式是否丢 |

---

## 4. Pipeline 后端的 JSON 结构

> 官方文档把 Pipeline 输出拆成 `model.json`、`middle.json`、`content_list.json` 三类。

## 4.1 `*_model.json`

这是**检测结果**，不是正文结果。典型字段有：

- `cls_id`
- `label`
- `score`
- `bbox`
- `index`

一个简化示意：

```json
[
  {
    "cls_id": 6,
    "label": "doc_title",
    "score": 0.97,
    "bbox": [275, 181, 1512, 292],
    "index": 3
  },
  {
    "cls_id": 22,
    "label": "text",
    "score": 0.92,
    "bbox": [275, 330, 524, 370],
    "index": 4
  }
]
```

### 你要注意

- 这里通常只有**块类别、位置、分数**等检测信息。
- **不要假设这里一定有 `text` 字段。**
- 如果你从这里直接“抽文本”，很可能一个字都抽不到，于是最后就是 **0 个 chunk**。

---

## 4.2 `*_middle.json`

这是最完整的中间结构，适合排查“正文丢在哪一层”。

### 顶层结构

```json
{
  "pdf_info": [...],
  "_backend": "pipeline",
  "_version_name": "x.y.z"
}
```

字段含义：

- `pdf_info`：每一页的解析结果数组
- `_backend`：后端类型，可能是 `pipeline` / `vlm` / `office`
- `_version_name`：MinerU 版本号

### `pdf_info`（每页）里常见字段

- `preproc_blocks`：预处理后的、尚未完成语义切分的块
- `page_idx`：页号，从 **0 开始**
- `page_size`：页面宽高 `[width, height]`
- `images`：图片块列表
- `tables`：表格块列表
- `interline_equations`：行间公式块
- `discarded_blocks`：被丢弃的块
- `para_blocks`：最终分段后的内容块

### 中间层级关系

官方文档给出的结构可以理解为：

```text
Level 1 blocks (table | image)
└── Level 2 blocks
    └── lines
        └── spans
```

### 更细一层的含义

#### 一级块（Level 1）

常见字段：

- `type`：一级块类型，主要是 `table` 或 `image`
- `bbox`
- `blocks`：内部的二级块

#### 二级块（Level 2）

常见字段：

- `type`
- `bbox`
- `lines`

支持的二级块类型，官方文档列了这些：

- `image_body`
- `image_caption`
- `image_footnote`
- `table_body`
- `table_caption`
- `table_footnote`
- `text`
- `title`
- `index`
- `list`
- `interline_equation`

#### line / span

- `line` 里通常有 `bbox`、`spans`
- `span` 里通常有：
  - `bbox`
  - `type`
  - `content` 或 `img_path`

### 什么时候该看 `middle.json`

你有以下情况时，别只盯 `content_list.json`：

- 觉得明明文档里有文字，但内容没有进 chunk
- 想知道一段内容是“被丢弃了”还是“根本没识别出来”
- 想确认图片、表格、caption、footnote 是否被正确绑定
- 想做更细粒度的后处理（例如 span 级合并）

---

## 4.3 `*_content_list.json`

这是**最适合直接做 chunk 的 legacy 结构**。

### 核心特点

- 是 `middle.json` 的**简化版**
- 内容按**阅读顺序**平铺
- 删除了很多复杂布局层级
- 每个块都能知道自己来自哪一页

### 基本形态

顶层就是一个数组：

```json
[
  {...},
  {...},
  {...}
]
```

每个元素是一个内容块。

### 常见 `type`

官方文档列出的类型包括：

- `image`
- `table`
- `chart`
- `text`
- `equation`
- `seal`
- `code`
- `list`
- `header`
- `footer`
- `page_number`
- `aside_text`
- `page_footnote`

### 所有块都几乎要看的公共字段

- `page_idx`：页号，从 **0 开始**
- `bbox`：块的边界框，范围映射到 **0–1000**
- `type`

### `text_level` 的意义

`text` 类型里可能有 `text_level`，它是标题层级标记：

- 没有 `text_level` 或 `0`：正文
- `1`：一级标题
- `2`：二级标题
- 以此类推

### 典型块结构

#### 1）普通正文 / 标题

```json
{
  "type": "text",
  "text": "The response of flow duration curves to afforestation",
  "text_level": 1,
  "bbox": [62, 480, 946, 904],
  "page_idx": 0
}
```

#### 2）图片块

```json
{
  "type": "image",
  "img_path": "images/xxx.jpg",
  "image_caption": ["Fig. 1 ..."],
  "image_footnote": [],
  "bbox": [62, 480, 946, 904],
  "page_idx": 1
}
```

#### 3）公式块

```json
{
  "type": "equation",
  "img_path": "images/xxx.jpg",
  "text": "$$ ... $$",
  "text_format": "latex",
  "bbox": [62, 480, 946, 904],
  "page_idx": 2
}
```

#### 4）表格块

```json
{
  "type": "table",
  "img_path": "images/xxx.jpg",
  "table_caption": ["Table 2 ..."],
  "table_footnote": ["..."],
  "table_body": "<html>...</html>",
  "bbox": [62, 480, 946, 904],
  "page_idx": 5
}
```

#### 5）代码块 / 算法块

`code` 可能带：

- `sub_type`：`code` 或 `algorithm`
- `code_body`
- `code_caption`
- `code_footnote`

#### 6）列表块

`list` 可能带：

- `sub_type`
- `list_items`

### 这就是为什么它适合做 chunk

因为你拿到它以后，可以非常直接地写规则：

- `text` → 拿 `text`
- `equation` → 拿 `text`
- `table` → 拿 `table_caption + table_body + table_footnote`
- `image` → 拿 `image_caption`，必要时保留 `img_path`
- `code` → 拿 `code_caption + code_body`
- `list` → 拿 `list_items`

---

## 5. VLM 后端的 JSON 结构

> **官方特别提醒：VLM 后端从 2.5 起输出变化很大，和 pipeline 后端不向后兼容。**  
> 所以写解析器时，**一定不要把 pipeline 的 schema 强套到 VLM 上。**

## 5.1 `*_model.json`

VLM 的 `model.json` 和 pipeline 的 `model.json` 不一样。

### 顶层结构

- 外层 list：页
- 内层 list：该页的内容块

也就是：

```json
[
  [ {页1块1}, {页1块2}, ... ],
  [ {页2块1}, {页2块2}, ... ]
]
```

### 单个块至少有这些字段

- `type`
- `bbox`
- `angle`
- `content`

有些块还会带：

- `score`
- `block_tags`
- `content_tags`
- `format`

### 支持的 `type`（官方列举）

- `text`
- `title`
- `equation`
- `image`
- `image_caption`
- `image_footnote`
- `table`
- `table_caption`
- `table_footnote`
- `phonetic`
- `code`
- `code_caption`
- `ref_text`
- `algorithm`
- `list`
- `header`
- `footer`
- `page_number`
- `aside_text`
- `page_footnote`

### VLM `model.json` 的坐标

官方文档明确写了：

- `bbox = [x0, y0, x1, y1]`
- 原点在页面左上角
- 坐标是 **[0,1] 归一化比例**

### 但仍然不建议直接拿它切 chunk

因为它仍然更偏“原始模型输出”，而不是专门为 chunk 设计的阅读顺序正文列表。

---

## 5.2 `*_middle.json`

VLM 的 `middle.json` 和 pipeline 整体相似，但官方文档明确给了几处差异：

### 差异点 1：`list` 变成二级块，并带 `sub_type`

- `text`：普通列表
- `ref_text`：参考文献风格列表

### 差异点 2：新增 `code` 块，并带 `sub_type`

- `code`
- `algorithm`

### 差异点 3：`discarded_blocks` 里可能出现更多类型

- `header`
- `footer`
- `page_number`
- `aside_text`
- `page_footnote`

### 差异点 4：所有块都带 `angle`

取值通常是：

- `0`
- `90`
- `180`
- `270`

这意味着：如果你写了一个只适配 pipeline 的解析器，而没有处理 `angle` / `sub_type` / `discarded_blocks` 的额外类型，**很容易在 VLM 下漏块，甚至切不出正文块。**

---

## 5.3 `*_content_list.json`

VLM 下的 `content_list.json` 仍然是“适合 chunk 的扁平列表”，但相较 pipeline 有扩展。

### VLM 扩展点

#### 1）`code`

- `sub_type`: `code` 或 `algorithm`
- `code_body`
- 可选 `code_caption`

#### 2）`list`

- `sub_type`: `text` 或 `ref_text`
- `list_items`

#### 3）辅助块也会输出

官方文档明确说，`discarded_blocks` 中的内容也会输出到这里，例如：

- `header`
- `footer`
- `page_number`
- `aside_text`
- `page_footnote`

### 这会带来一个很实际的问题

如果你的 chunk 逻辑是：

```python
只保留 type == "text"
```

那么你会：

- 丢掉 `equation`
- 丢掉 `code`
- 丢掉 `list`
- 丢掉 `table`
- 丢掉 `image_caption` 相关语义
- 在某些 PDF 上最后几乎没剩东西

这也是“解析有结果，但 chunk 数是 0 或极少”的典型根因。

---

## 6. 3.0+ 的 `content_list_v2.json`

> 官方文档把它叫做：**development version, subject to change**。  
> 也就是说，它是新结构，但你要注意后续版本变动风险。

### 核心特点

- 3.0 新增
- 所有后端都会输出
- **按页分组**
- 使用统一的 `type + content` 结构
- 比 legacy `content_list.json` 更适合新代码做结构化消费

### 顶层结构

顶层不是扁平数组，而是：

```json
[
  [ page0_block0, page0_block1, ... ],
  [ page1_block0, page1_block1, ... ]
]
```

### 每个块的公共字段

- `type`
- `content`
- `bbox`（可选，0–1000）
- `anchor`（可选）

### 官方列出的常见 `type`

- `title`
- `paragraph`
- `equation_interline`
- `image`
- `table`
- `chart`
- `seal`
- `code`
- `algorithm`
- `list`
- `index`
- `page_header`
- `page_footer`
- `page_number`
- `page_aside_text`
- `page_footnote`

### 一个简化示意

```json
[
  [
    {
      "type": "title",
      "content": {
        "title_content": [
          {"type": "text", "content": "1 Introduction"}
        ],
        "level": 1
      },
      "bbox": [83, 121, 917, 156]
    },
    {
      "type": "paragraph",
      "content": {
        "paragraph_content": [
          {"type": "text", "content": "...."}
        ]
      }
    }
  ]
]
```

### 我对接入建议的判断

#### 如果你们是老代码、想最省事
继续用 `content_list.json`，兼容成本最低。

#### 如果你们是新代码、准备长期维护
优先评估 `content_list_v2.json`，因为它：

- 页面边界天然清楚
- 各类型都收敛到 `type + content`
- 对后续结构化切块更友好

#### 但要注意
由于官方文档明确写了它仍是“development version”，所以：
- 生产环境建议做 schema 版本判断
- 不要把字段写死成“永远不会变化”

---

## 7. 哪些字段里真的有“可切块文本”

这是最实用的一节。

## 7.1 不同类型应读哪些字段

| type | 主要取值字段 | 说明 |
|---|---|---|
| `text` | `text` | 正文/标题 |
| `equation` | `text` | 公式通常是 LaTeX |
| `table` | `table_caption`, `table_body`, `table_footnote` | `table_body` 往往是 HTML |
| `image` | `image_caption`, `image_footnote` | 仅 `img_path` 本身没语义 |
| `code` | `code_body`, `code_caption`, `code_footnote` | 不一定有 `text` |
| `list` | `list_items` | 不一定有 `text` |
| `header/footer/page_number/...` | 通常建议过滤 | 看业务需求 |

### 一个错误示例

```python
text = block.get("text", "").strip()
if text:
    chunks.append(text)
```

这个写法会漏掉：

- 表格
- 图片说明
- 代码块
- 列表
- 某些 V2 结构块

于是最后你会觉得：“JSON 明明很大，为什么 chunk 是 0 或很少？”

### 更合理的思路

按 `type` 分发：

```python
if type == "text":
    use block["text"]
elif type == "equation":
    use block["text"]
elif type == "table":
    use caption + html + footnote
elif type == "image":
    use caption + footnote
elif type == "code":
    use caption + body + footnote
elif type == "list":
    use list_items
```

---

## 8. 推荐的 chunk 规则

这里给一个**够稳、够简单**的建议版。

## 8.1 先做块级清洗

建议先过滤掉这些辅助类型：

- `header`
- `footer`
- `page_number`
- `aside_text`
- `page_footnote`

除非你们业务明确需要页眉、页脚、边注、脚注。

---

## 8.2 再做块级文本归一化

### `text`
直接取 `text`

### `equation`
取 `text`（通常是 LaTeX），必要时在前面加 `[Equation]`

### `table`
建议拼成：

```text
[Table]
Caption: ...
Body(HTML): ...
Footnote: ...
```

### `image`
如果你们做的是纯文本 RAG，建议至少保留：

```text
[Image]
Caption: ...
Footnote: ...
```

否则图片块没有语义，后面召回不到。

### `code`
建议拼成：

```text
[Code]
Caption: ...
Body: ...
Footnote: ...
```

### `list`
把 `list_items` 用换行拼起来。

---

## 8.3 用标题层级增强 chunk

如果 `text_level` 存在，可以这样做：

- `text_level == 1`：开新 section
- `text_level == 2`：开 subsection
- 后续正文归并到最近标题下

这会比“单纯按长度切”更稳。

---

## 8.4 按页保留来源信息

无论你最终怎么拼块，建议都把下面这些元数据带上：

- `page_idx`
- `bbox`
- `type`
- 可能的标题路径（如 H1/H2）

这样后续：

- 回溯原文页码
- 在前端高亮定位
- debug 错块 / 漏块

都会轻松很多。

---

## 9. 为什么会出现“某一页看起来没表格 / 没内容”

### 9.1 跨页表格默认可能被合并

官方讨论里提到：跨页表格默认会合并，保留在前页位置；如果你想保留逐页表格内容，需要关闭表格合并。

这意味着：

- PDF 第 10 页开始一个表
- 第 11 页是续表
- 你按“每页至少有一个 table block”去扫
- 第 11 页可能看起来就“没有表格块”

### 9.2 这不一定是解析失败

可能只是**合并策略**导致它不再以“单页一个 block”的方式出现。

---

## 10. 一个稳妥的解析优先级

### 方案 A：只想快速稳定出 chunk
直接用 `content_list.json`

### 方案 B：项目新建、版本 >= 3.0
优先评估 `content_list_v2.json`

### 方案 C：发现 chunk 数异常
同时检查：

1. `content_list.json`
2. `middle.json`
3. `layout.pdf`
4. `span.pdf`（pipeline）

### 方案 D：千万别这样做
直接从 `_model.json` 里找 `text`

---

## 11. 建议给 CC 的实现 checklist

### 最低限度必须做的事

- [ ] 先判断当前拿到的是哪种文件：`model` / `middle` / `content_list` / `content_list_v2`
- [ ] 再判断 backend：`pipeline` 还是 `vlm`
- [ ] 解析时不要只读 `text`
- [ ] 使用 `page_idx`（或 V2 的页级数组）保留页信息
- [ ] 过滤页眉页脚等辅助块
- [ ] 对 `table` / `code` / `list` / `equation` 单独处理
- [ ] 对 `text_level` 做标题归并
- [ ] chunk 数异常时，先看 `layout.pdf` / `span.pdf` / `middle.json`

### 最容易犯错的点

- [ ] 把 `_model.json` 当正文来源
- [ ] 把 `content_list.json` 当成“每个 block 一定有 text”
- [ ] 忽略 `page_idx` 从 0 开始
- [ ] 把 VLM schema 当 pipeline schema 用
- [ ] 没考虑跨页表格合并
- [ ] 把所有非 `text` 类型全删了

---

## 12. 我建议你们实际采用的策略

如果目标是“稳定产出 chunk，而不是研究 MinerU 内部所有细节”，建议：

### 推荐落地版

1. **主输入：`content_list.json`**
2. **如果是 3.0+ 且愿意适配：评估 `content_list_v2.json`**
3. **chunk 前统一做类型分发**
4. **保留 `page_idx + bbox + type` 元数据**
5. **发现 0 chunk 时，第一时间检查是不是误读了 `_model.json`**

这是我认为性价比最高、最不容易翻车的做法。

---

## 13. 一个很短的伪代码模板

```python
def block_to_text(block):
    t = block.get("type")

    if t == "text":
        return block.get("text", "").strip()

    if t == "equation":
        return block.get("text", "").strip()

    if t == "table":
        parts = []
        parts += block.get("table_caption", [])
        if block.get("table_body"):
            parts.append(block["table_body"])
        parts += block.get("table_footnote", [])
        return "\n".join(x for x in parts if x)

    if t == "image":
        parts = []
        parts += block.get("image_caption", [])
        parts += block.get("image_footnote", [])
        return "\n".join(x for x in parts if x)

    if t == "code":
        parts = []
        parts += block.get("code_caption", [])
        if block.get("code_body"):
            parts.append(block["code_body"])
        parts += block.get("code_footnote", [])
        return "\n".join(x for x in parts if x)

    if t == "list":
        return "\n".join(block.get("list_items", []))

    return ""
```

---

## 14. 最后一句话总结

> **MinerU 里“最容易让人误判”的点，不是它没解析出内容，而是你拿错了 JSON。**  
> 对“切 chunk”这件事来说：  
> **`content_list.json` / `content_list_v2.json` 是入口，`middle.json` 是调试器，`model.json` 不是正文源。**

---

## 参考来源

1. MinerU 官方输出文件文档（Output File Format）  
   https://opendatalab.github.io/MinerU/reference/output_files/

2. MinerU 官方 Changelog  
   https://opendatalab.github.io/MinerU/reference/changelog/

3. GitHub Issue #4540：关于 `_model.json` 不含文本、`_content_list.json` 才是按页正文来源的说明  
   https://github.com/opendatalab/MinerU/issues/4540

4. GitHub Discussion #4546：官方维护者说明可用 `content_list.json` 的 `page_idx` 做分页内容归属  
   https://github.com/opendatalab/MinerU/discussions/4546

5. GitHub Discussion #3592：关于 `page_idx` / 页码元数据的讨论  
   https://github.com/opendatalab/MinerU/discussions/3592
