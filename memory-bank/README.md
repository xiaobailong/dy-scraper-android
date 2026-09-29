# memory-bank 索引（**只读需要的那 1 个文件**，别全读）

- 文件代号：`I` = `issues-solved.md`（已定位问题）　`P` = `pitfalls.md`（踩坑）　`D` = `decisions.md`（取舍）
- `archive/` **默认不读**（条目给指针才读那节）；**写**条目看 `WRITING.md`；规矩见 `.clinerules/memory-bank.md`
- 上限：本索引 2.5KB，`I`/`P`/`D` 各 12KB（超了先归档；带「已归档」字样的详情在 archive）

## 症状 → 条目

> 条目见下表；新问题先查表再动手，命中就复用结论。

| 症状 / 报错 | 条目 | 文件 |
| --- | --- | --- |
| 视频页 `【视频】0 个`、视频永远下不下来 | ISSUE-001（相关 PIT-001 PIT-002 PIT-003 / ADR-003 ADR-004） | I / P / D |
| 视频页仍 `【视频】0 个`（重放 body=0 / 整页 HTML 已复现过） | ISSUE-001（PIT-006 PIT-007 PIT-008 / ADR-006） | I / P / D |
| 详情 API 重放拿不到 body（缺 a_bogus 签名） | PIT-006 | P |
| SSR 变量 hydration 后被删 → 事后 dump 拿不到 | PIT-007 | P |
| 详情 API 去重键必须带 aweme_id | PIT-008 | P |
| 图片被误删（6 张 → 2 张；aHash 冒充 pHash） | PIT-009 / ADR-007 | P / D |
| 大文件 MD5 用 readBytes() 会 OOM | PIT-010 | P |
| 详情响应体到底怎么拿（旁听 vs 重放） | ADR-006（旧 ADR-003） | D |
| 进度日志恒为 `0/N` | ISSUE-002 | I |
| 汇总「文件总数 ≠ 成功+失败+跳过」 | ISSUE-003 | I |
| `evaluateJavascript` 取 Promise 结果只拿到 `{}`（长度 2） | PIT-001 | P |
| 网络请求里的视频 URL 匹配不到（douyinvod 无 .mp4 后缀） | PIT-002 | P |
| 同一个视频下了好几份（不同码率/清晰度） | PIT-003 | P |
| 本机跑 gradle 报 `JAVA_HOME is set to an invalid directory` | PIT-004 | P |
| 改 `.bat` / `memory-bank` 后换行或 BOM 不对 | PIT-005 | P |
| tag / Release 名带 `-b<versionCode>` 后缀 | ADR-005 | D |
| 详情 API 响应体抓不到 / SSR 数据怎么取 | ADR-003 ADR-004 | D |