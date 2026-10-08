"""Pure presentation rules; never convert absent or uncertain values into zero."""
FIELDS = [("report_date", "报告日期"), ("total", "总睡眠时长"), ("bedtime", "入睡时间"),
          ("wake_time", "醒来时间"), ("score", "睡眠评分"), ("deep", "深睡"),
          ("light", "浅睡"), ("rem", "快速眼动"), ("awake", "清醒时长"),
          ("night", "夜间睡眠时长"), ("naps", "零星小睡时长")]
STATES = dict(waiting="等待", running="运行中", needs_user="需要用户处理", completed="完成", failed="失败", cancelled="已取消")
DETERMINACY = dict(observed="页面观测", missing="未读取", conflict="冲突，需要核对", uncertain="不确定，需要核对", verified="已核对", user_confirmed="用户确认年份，页面核对月日", mismatch="日期不匹配")


def rows(task):
    result = task.get("result") or {}
    fields = result.get("fields") or {}
    records = []
    for key, label in FIELDS:
        evidence = result.get("report_date") if key == "report_date" else fields.get(key)
        evidence = evidence if isinstance(evidence, dict) else {}
        value = evidence.get("value")
        state = evidence.get("state", "missing")
        unit = evidence.get("unit") or ""
        display = "未读取" if value is None else str(value)
        if value is not None and unit == "minute":
            display += " 分钟"
        elif value is not None and unit == "point":
            display += " 分"
        records.append([label, display, unit, evidence.get("source", "—"), DETERMINACY.get(state, state),
                        "；".join(map(str, evidence.get("evidence") or [])), evidence.get("reason") or ""])
    return records


def needs_review(task):
    result = task.get("result") or {}
    if task.get("state") != "completed" or not result.get("success"):
        return True
    fields = result.get("fields") or {}
    evidence = [result.get("report_date") or {}, *(fields.get(key) or {} for key, _ in FIELDS if key != "report_date")]
    return any(not isinstance(e, dict) or e.get("value") is None or e.get("state") not in {"observed", "verified"} for e in evidence)
