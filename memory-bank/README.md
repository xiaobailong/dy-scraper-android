# memory-bank 索引（**只读需要的那 1 个文件**，别全读）

- 代号：`I`=issues-solved（已定位）　`P`=pitfalls（坑）　`D`=decisions（取舍）
- `archive/` **默认不读**（条目给了指针才读那节）；**写**条目看 `WRITING.md`；规矩见 `.clinerules/memory-bank.md`
- 上限：本索引 2.5KB，`I`/`P`/`D` 各 12KB（超了先归档；详情在 archive）

## 症状 → 条目

> 新问题先查表再动手，命中就复用结论。

| 症状 / 报错 | 条目 | 文件 |
| --- | --- | --- |
| 视频页「【视频】0 个」/ 视频下不来（重放 body=0） | ISSUE-001（PIT-001~003、006~008，已归档） | I / P |
| 详情 body 拿不到 / 到底怎么拿（缺 a_bogus、旁听 vs 重放） | PIT-006 / ADR-006（旧 ADR-003） | P / D |
| SSR 变量 hydration 后被删 | PIT-007 | P |
| 详情去重键必须带 aweme_id | PIT-008 | P |
| 图片被误删（aHash 冒充 pHash） | PIT-009 / ADR-007 | P / D |
| 大文件 MD5 用 readBytes() 会 OOM | PIT-010 | P |
| 详情响应体到底怎么拿（旁听 vs 重放） | ADR-006（旧 ADR-003） | D |
| 进度日志恒为 `0/N` | ISSUE-002 | I |
| 汇总「文件总数 ≠ 成功+失败+跳过」 | ISSUE-003 | I |
| 加密页列表比手机里少文件（目录按创建者隔离） | ISSUE-004（需“所有文件访问”） | I |
| 本机 adb devices 恒为空（华为接口缺 GUID） | PIT-011 | P |
| `evaluateJavascript` 取 Promise 只拿到 `{}` | PIT-001 | P |
| 视频 URL 匹配不到（douyinvod 无 .mp4 后缀） | PIT-002 | P |
| 同一视频下了好几份（不同码率） | PIT-003 | P |
| 本机 gradle 报 `JAVA_HOME is set to an invalid directory` | PIT-004 | P |
| 改 `.bat` / `memory-bank` 后换行或 BOM 不对 | PIT-005 | P |
| tag / Release 名带 `-b<versionCode>` | ADR-005（已归档） | D |
| SSR 数据怎么取 | ADR-006（已归档；ADR-003/004） | D |
| 抓取落到抖音滑块/验证页 | ADR-012（相关 PIT-012） | D / P |
| adb 中断同一行语句 / `tmp\` 丢失 | PIT-012 | P |
| 加密页下拉刷新 + 首行全选 | ADR-008 | D |
| 加密页长文件名显示不全 | ADR-009 | D |
| 预览页去掉「×」，改用系统返回 | ADR-010 | D |
| 加密页计数并入底部状态栏 | ADR-011 | D |
| 点「加密」后文件个数翻倍（明文没删掉） | ISSUE-005（PIT-013） | I / P |
| 顶栏 / 底栏 / 系统状态栏配色（全局纯白） | ADR-013 | D |