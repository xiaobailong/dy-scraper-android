# 知识库规矩（memory-bank）

数据在 `memory-bank/`：索引 `README.md`、条目 `I`/`P`/`D`、写法 `WRITING.md`、归档 `archive/`。

## 1. 读取：检索式，禁止 read all
1. 只读 `memory-bank/README.md`（索引）。
2. 只读命中编号所在的**那 1 个文件**（`I` = `issues-solved.md`、`P` = `pitfalls.md`、`D` = `decisions.md`）；
   `archive/` 默认不读，只有条目给指针时才读那一节。
3. 命中即复用条目结论；只有现场证据与条目矛盾时才重查，并在同一轮更新该条目。
4. 老问题复发：先跑条目的「复发判据」定论 → **更新原条目**（不新建重复条目）。
5. 回复里一句话报告命中编号（未命中就写"未命中，按新问题排查"）。

## 2. 回填：任务结束前，不许只做不写
- 已定位问题 → `issues-solved.md`；踩坑 → `pitfalls.md`；取舍 → `decisions.md`；复发 → 更新原条目；
  没定位清楚 → 标 `状态: 未定位` + 已知证据 + 下一步验证。
- 模板 / 阈值 / 归档 / 红线 → `memory-bank/WRITING.md`（**只在要写条目时读**）。
- 同步更新索引；结尾列出本次条目编号 + `tmp\` 是否清空。