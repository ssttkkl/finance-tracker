set -euo pipefail
repo=$(pwd -P)
report="$RUNNER_TEMP/cash-review-workspace-reports"
work="$RUNNER_TEMP/cash-review-workspace-job"
base=9e1153c9b936cfb64a8a048743c2cd04c4346585
phase=$1
mkdir -p "$report" "$work"

if [[ "$phase" = build ]]; then
    git rev-parse HEAD > "$report/candidate.txt"
    printf '%s\n' "$base" > "$report/baseline.txt"
    git worktree add --detach "$work/baseline" "$base"
    for mode in candidate baseline; do
        source="$repo"
        if [[ "$mode" = baseline ]]; then source="$work/baseline"; fi
        targets=(:cli:stageNpm)
        if [[ "$mode" = candidate ]]; then targets+=(:backend:installDist :client-operations:dumpPerformanceAbi); fi
        "$source/compose/gradlew" -p "$source/compose" --no-configuration-cache "${targets[@]}" > "$report/build-$mode.log" 2>&1
        mkdir -p "$work/$mode-package" "$work/$mode-install"
        name=$(npm pack "$source/compose/cli/build/npm" --silent --pack-destination "$work/$mode-package")
        archive="$work/$mode-package/$name"
        printf '%s\n' "$archive" > "$work/$mode-archive"
        npm install --prefix "$work/$mode-install" --ignore-scripts --engine-strict --no-audit --no-fund "$archive"
    done
    ./compose/gradlew -p compose --stop
    node compose/client-operations/perf/gate.mjs inventory compose/client-operations/perf/budgets.json compose/client-operations/build/performance/client-operations.abi
    python3 compose/client-operations/perf/fixtures/large-cash.py --output "$work/fixture" \
        --backend-install "$repo/compose/backend/build/install/backend"
    exit
fi

[[ "$phase" = measure ]]
export FT_PERF_BACKEND_INSTALL="$repo/compose/backend/build/install/backend"
export FT_PERF_WORKSPACE_DIAGNOSTIC_FIXTURE="$work/fixture"
for round in 1 2; do
    modes=(memory shared sqlite file)
    if [[ "$round" = 2 ]]; then modes=(file sqlite shared memory); fi
    for medium in "${modes[@]}"; do
        name="ft_workset_${medium}_${round}_test"
        psql "$FT_PERF_PG_ADMIN_URL" -Xq -v ON_ERROR_STOP=1 -c "CREATE DATABASE $name"
        export FT_TEST_POSTGRES_URL="${FT_PERF_PG_ADMIN_URL%/*}/$name"
        if [[ "$medium" = memory ]]; then
            mode=baseline
            unset FT_PERF_REVIEW_WORKSPACE
        else
            mode=candidate
            export FT_PERF_REVIEW_WORKSPACE="$medium"
        fi
        export FT_PERF_WORKSPACE_DIAGNOSTIC_INSTALL="$work/$mode-install"
        export FT_PERF_CLI_PACKAGE
        FT_PERF_CLI_PACKAGE=$(cat "$work/$mode-archive")
        export FT_PERF_ARTIFACTS="$report/$round-$medium"
        export FT_PERF_EVENTS="$FT_PERF_ARTIFACTS/measurements.jsonl"
        mkdir -p "$FT_PERF_ARTIFACTS"
        bench_exit=0
        node compose/client-operations/perf/large-bench.mjs > "$FT_PERF_ARTIFACTS/runtime.log" 2>&1 || bench_exit=$?
        printf '%s\n' "$bench_exit" > "$FT_PERF_ARTIFACTS/exit.txt"
        node compose/client-operations/perf/gate.mjs evaluate compose/client-operations/perf/budgets.json "$FT_PERF_EVENTS" \
            maximum "$FT_PERF_ARTIFACTS/fixed-client-evaluation.json" > "$FT_PERF_ARTIFACTS/fixed-client-evaluation.log" 2>&1 || true
        printf '%s %s exit=%s\n' "$round" "$medium" "$bench_exit"
        psql "$FT_PERF_PG_ADMIN_URL" -Xq -v ON_ERROR_STOP=1 -c "DROP DATABASE $name"
    done
done
