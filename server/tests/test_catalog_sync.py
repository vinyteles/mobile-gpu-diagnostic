"""Keep server/catalog.py in sync with the Android sources and the bundled fixture."""

import hashlib
import re

from server.catalog import (
    BATCH_NAMES,
    CONFIGURATION_MODELS,
    FIXTURE_SHA256,
    PROTOCOL_ID,
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


def test_configurations_match_model_catalog():
    source = (KOTLIN / "ModelCatalog.kt").read_text()
    assert f'PROTOCOL_ID = "{PROTOCOL_ID}"' in source
    models = kotlin_models(source)
    by_variable = {variable: model_id for model_id, (variable, _) in models.items() if variable}
    by_variable.update({"MODELS[0]": "gemma-mixed48", "MODELS[1]": "gemma-int8"})
    block = source[source.index("val BENCHMARK_CONFIGS"):source.index("val BENCHMARK_MODELS")]
    expected = {}
    for reference, backend in re.findall(r'BenchmarkConfig\(([\w\[\]]+), "(\w+)"', block):
        model_id = by_variable[reference]
        expected[f"{model_id}-{backend}-replay"] = models[model_id][1]
    assert list(expected) == list(CONFIGURATION_MODELS)
    assert expected == CONFIGURATION_MODELS


def test_batch_names_match():
    source = (KOTLIN / "ModelCatalog.kt").read_text()
    for number, name in BATCH_NAMES.items():
        assert f'"{name}"' in source, number
