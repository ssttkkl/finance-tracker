from pathlib import Path
import shutil
import sys

candidate, baseline = map(Path, sys.argv[1:])
shared = [
    "compose/client-operations/src/commonMain/kotlin/com/finance/tracker/client/ClientCashImportOperations.kt",
    "compose/client-operations/src/commonMain/kotlin/com/finance/tracker/client/CashImportReviewOperations.kt",
    "compose/cli/src/jsMain/kotlin/com/finance/tracker/cli/NodeHost.kt",
]
for relative in shared:
    shutil.copyfile(candidate / relative, baseline / relative)
plan = baseline / "compose/business-domain/src/commonMain/kotlin/com/finance/tracker/business/domain/LocalCashImportBusinessPlan.kt"
source = plan.read_text(encoding="utf-8")
for old, new, count in [
    ("    workspace: CashImportReviewWorkspace? = null,\n", "    workspace: CashImportReviewWorkspace? = null, sample: (String) -> Unit = {},\n", 2),
    ("progress, now, workspace)", "progress, now, workspace, sample)", 1),
    ("    val frozen = freezeLocalCashImportPreviewPlan(plan, workspace)", '    sample("planning_complete")\n    val frozen = freezeLocalCashImportPreviewPlan(plan, workspace)\n    sample("plan_frozen")', 1),
    ("    return CalculatedImportPlan(plan, frozen, changes)", '    sample("write_set")\n    return CalculatedImportPlan(plan, frozen, changes)', 1),
]:
    assert source.count(old) == count, old
    source = source.replace(old, new)
plan.write_text(source, encoding="utf-8")
