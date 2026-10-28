"""CI 分片完整执行全部测试，并把收集及执行失败交给汇总检查。"""
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / 'scripts/run_backend_tests.py'


def _run(tmp_path, shard, count):
    return subprocess.run([sys.executable, str(RUNNER), '--shard-index', str(shard),
        '--shard-count', str(count)], cwd=tmp_path, capture_output=True, text=True,
        env={**os.environ, 'PYTHONPATH': ''})


def test_shards_cover_each_test_once_and_keep_files_together(tmp_path):
    for number, tests in enumerate((3, 2, 1)):
        (tmp_path / f'test_{number}.py').write_text('\n'.join(
            f'def test_{index}():\n    with open("seen-{number}", "a") as stream: stream.write("{index}\\n")'
            for index in range(tests)))
    for shard in range(2):
        result = _run(tmp_path, shard, 2)
        assert result.returncode == 0, result.stdout + result.stderr
    assert [(tmp_path / f'seen-{number}').read_text().splitlines() for number in range(3)] == [
        ['0', '1', '2'], ['0', '1'], ['0']]


def test_shard_propagates_test_and_collection_failures(tmp_path):
    source = tmp_path / 'test_failure.py'
    source.write_text('def test_failure():\n    assert False\n')
    assert _run(tmp_path, 0, 1).returncode == 1
    source.write_text('raise RuntimeError("collection failed")\n')
    assert _run(tmp_path, 0, 1).returncode == 2


def test_empty_shard_cannot_pass(tmp_path):
    (tmp_path / 'test_only.py').write_text('def test_only(): pass\n')
    results = [_run(tmp_path, index, 2) for index in range(2)]
    assert sorted(result.returncode for result in results) == [0, 5]
