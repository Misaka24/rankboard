from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Unexpected match count in {path}: {count}")
    file.write_text(text.replace(old, new), encoding="utf-8")


replace_once("gradle.properties", "mod_version=1.5.6", "mod_version=1.5.7")
replace_once("README.md", "当前版本：`1.5.6`", "当前版本：`1.5.7`")
replace_once(
    "README.md",
    "### 1.5.6 数据正确性说明\n",
    '''### 1.5.7 周期可用性修复

- 最近一日、最近一周、最近一月和自定义日期不再因为缺少完整零点快照而全部报错。
- 预设周期会从所选范围内最早的可信快照开始计算，并明确返回实际开始日期与“不完整周期”警告。
- 游戏内日榜、周榜、月榜和年榜允许显示部分周期，标题会标记“（部分）”，不再直接拒绝查询。
- 完整零点边界仍会被保留并优先使用；部分周期不会伪装成完整周期。
- 历史结束边界仍保持严格校验，避免使用错误日期制造大幅偏差。

### 1.5.6 数据正确性说明
''',
)
