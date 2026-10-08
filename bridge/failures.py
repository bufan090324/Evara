"""Stable fault boundaries; diagnostics contain codes, not health evidence."""
LABELS = {"model_request": "模型请求", "tool": "工具执行", "phone_task": "手机任务", "parsing": "数据解析"}


def task_failure(task):
    report = task.get("result") or task.get("report") or {}
    date = report.get("report_date") if isinstance(report, dict) else None
    if isinstance(date, dict) and date.get("state") in {"conflict", "uncertain", "missing", "mismatch"}:
        return {"category": "parsing", "code": "DATE_UNCONFIRMED", "message": "报告日期未确认，请核对日期证据"}
    if task.get("state") in {"failed", "cancelled", "needs_user"}:
        return {"category": "phone_task", "code": str(task.get("state")).upper(),
                "message": "手机任务未完成，请核对任务步骤和原因"}
    # Retain uncertainty; a model reply cannot promote incomplete evidence.
    if isinstance(report, dict):
        fields = report.get("fields", report)
        if isinstance(fields, dict) and any(isinstance(v, dict) and v.get("state") in {"conflict", "uncertain", "missing"} for v in fields.values()):
            return {"category": "parsing", "code": "EVIDENCE_UNCONFIRMED", "message": "报告存在缺失、不确定或冲突，请核对原始证据"}
    return None


def display(error):
    category = getattr(error, "category", "tool")
    return "[" + LABELS.get(category, category) + "/" + getattr(error, "code", "VALIDATION") + "] " + str(error)
