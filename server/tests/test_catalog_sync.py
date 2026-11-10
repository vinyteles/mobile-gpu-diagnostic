"""Keep server/catalog.py in sync with the Android sources and the bundled fixture."""

import hashlib
import re

from server.catalog import (
    BATCH_CONFIGURATIONS,
    BATCH_NAMES,
    CONFIGURATION_MODELS,
    FIXTURE_SHA256,
    PROTOCOL_ID,
    TOOL_PROTOCOL_ID,
    TOOL_FIXTURE_SHA256,
)
from server.tests.conftest import (
    FIXTURE,
    REPO_ROOT,
)

KOTLIN = REPO_ROOT / "app/src/main/java/com/cemig/gpudiagnostic"


def kotlin_models(source: str) -> dict[str, tuple[str, str]]:
    """Return Kotlin model variable/list reference -> (model id, sha256)."""
    models = {}
    for match in re.finditer(r'(?:val (\w+) = )?ModelSpec\("([\w-]+)",.*?"([0-9a-f]{64})"', source, re.S):
        models[match.group(2)] = (match.group(1), match.group(3))
    return models


def test_fixture_digest():
    assert hashlib.sha256(FIXTURE).hexdigest() == FIXTURE_SHA256
    tool = REPO_ROOT / "app/src/main/assets/tool-questions.json"
    assert hashlib.sha256(tool.read_bytes()).hexdigest() == TOOL_FIXTURE_SHA256


def test_configurations_match_model_catalog():
    source = (KOTLIN / "ModelCatalog.kt").read_text()
    assert f'PROTOCOL_ID = "{PROTOCOL_ID}"' in source
    models = kotlin_models(source)
    by_variable = {variable: model_id for model_id, (variable, _) in models.items() if variable}
    by_variable.update({"MODELS[0]": "gemma-mixed48", "MODELS[1]": "gemma-int8"})
    block = source[source.index("val BENCHMARK_CONFIGS"):source.index("val BENCHMARK_MODELS")]
    expected = {}
    for reference, backend, arguments in re.findall(r'BenchmarkConfig\(([\w\[\]]+), "(\w+)"([^\n]*)', block):
        model_id = by_variable[reference]
        suffix = ('f6' if 'retrieval = "f6"' in arguments else 'mobileragplus') + '-tools-v1' if 'matrixCase' in arguments else 'replay'
        policy = re.search(r'toolPolicy = "(\w+)"', arguments)
        if policy:
            suffix = ('f6' if 'retrieval = "f6"' in arguments else 'mobileragplus') + '-' + policy[1] + '-v3'
        expected[f"{model_id}-{backend}-{suffix}"] = models[model_id][1]
    assert list(expected) == list(CONFIGURATION_MODELS)
    assert expected == CONFIGURATION_MODELS


def test_batch_names_match():
    source = (KOTLIN / "ModelCatalog.kt").read_text()
    for number in range(101, 124):
        assert f'{number} -> "{BATCH_NAMES[number]}"' in source
    assert [BATCH_NAMES[number] for number in range(1, 6)] == [
        "Batch 1 · Previously tested GPUs", "Batch 2 · New LiteRT GPUs",
        "Batch 3 · Original Gemma", "Batch 4 · LFM3 CPU", "Batch 5 · Energy CPU",
    ]


def test_profiles_map_to_catalog_order_and_legacy_groups():
    ids = list(CONFIGURATION_MODELS)
    assert {profile: BATCH_CONFIGURATIONS[profile] for profile in range(101, 124)} == {
        101 + index: (configuration,) for index, configuration in enumerate(ids)
    }
    # Pre-split Android BATCH_CONFIG_IDS: 1 -> [0, 1], 2 -> [2, 3], 3 -> [4, 8], 4 -> [5], 5 -> [6, 7].
    legacy = {1: (0, 1), 2: (2, 3), 3: (4, 8), 4: (5,), 5: (6, 7)}
    assert {batch: BATCH_CONFIGURATIONS[batch] for batch in legacy} == {
        batch: tuple(ids[index] for index in indexes) for batch, indexes in legacy.items()
    }
    assert set(BATCH_CONFIGURATIONS) == set(BATCH_NAMES)
    # APK_CONFIGS = listOf(BENCHMARK_CONFIGS[BuildConfig.BENCHMARK_PROFILE - 101]).
    assert "BENCHMARK_CONFIGS[BuildConfig.BENCHMARK_PROFILE - 101]" in (KOTLIN / "ModelCatalog.kt").read_text()
