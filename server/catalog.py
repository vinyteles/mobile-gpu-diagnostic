"""Benchmark constants mirrored from the Android app (ModelCatalog.kt, ReportExporter.kt).

tests/test_catalog_sync.py parses the Kotlin sources so this copy cannot drift silently.
"""

import re

PROTOCOL_ID = "mobile-rag-replay-120-v2"
FIXTURE_SHA256 = "e43daa8d6887b372ad05a729c9dd53488fd29023bd228836c1e98060817c3cc4"
TOOL_PROTOCOL_ID = "mobile-rag-tools-120-v1"
TOOL_FIXTURE_SHA256 = "92d338da6a1c1ed05139a7e6896b9077794bb2eb716d1eb7645bceb10b0dd489"
PROTOCOL_FIXTURES = {PROTOCOL_ID: FIXTURE_SHA256, TOOL_PROTOCOL_ID: TOOL_FIXTURE_SHA256}
V3_PROTOCOL_ID = "mobile-rag-pipelines-120-v3"
V3_FIXTURE_SHA256 = "39713939db031fe2ca4e6229035b20d9b85a7250e8a3e997e0757401fcc4078a"
PROTOCOL_FIXTURES[V3_PROTOCOL_ID] = V3_FIXTURE_SHA256
QUESTION_COUNT = 120

# Configuration id -> pinned model SHA-256, in ModelCatalog.BENCHMARK_CONFIGS order.
CONFIGURATION_MODELS = {
    "gemma-mixed48-gpu-replay": "63a7374c71b404eb4f8cf8322beca2bc862061cba29adb368131c73e198f59e1",
    "lfm3-q4km-vulkan-replay": "3793e1e6e2266919d6e015287221b41100175a98eddaba92e111aed3b05c93c4",
    "lfm3-mixed4-litert-gpu-replay": "327486bf01d0b1eefd727b7aadec8627a2b8db143349262857ac98403109d72b",
    "gemma-base-mixed248-gpu-replay": "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
    "gemma-base-q8-vulkan-replay": "605d3c2647d7c58c1e4b5375ccb5702acf94c2611b4c8d4877812f8fdd32d053",
    "lfm3-q4km-cpu-replay": "3793e1e6e2266919d6e015287221b41100175a98eddaba92e111aed3b05c93c4",
    "gemma-int8-cpu-replay": "1a4a87c0f00b191cc4136f783e17640cc5ab58fc4c2a0d3ec21348c0b98d2e54",
    "gemma-mixed48-cpu-replay": "63a7374c71b404eb4f8cf8322beca2bc862061cba29adb368131c73e198f59e1",
    "gemma-base-mixed248-cpu-replay": "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
    "lfm3-q4km-vulkan-mobileragplus-tools-v1": "3793e1e6e2266919d6e015287221b41100175a98eddaba92e111aed3b05c93c4",
    "lfm3-tuned-q4km-vulkan-mobileragplus-tools-v1": "487c5a5530a599acc1827fffbf1b1fa17a5cdbe2f31082632fa3c572bdfcc8f3",
    "lfm3-q4km-vulkan-f6-tools-v1": "3793e1e6e2266919d6e015287221b41100175a98eddaba92e111aed3b05c93c4",
    "lfm3-tuned-q4km-vulkan-f6-tools-v1": "487c5a5530a599acc1827fffbf1b1fa17a5cdbe2f31082632fa3c572bdfcc8f3",
    "lfm3-tuned-q4-vulkan-mobileragplus-tools-v1": "94847d5bbcaae51a3cf63c6aed0063922e008521267d4ccdd364bf8533e85050",
    "lfm3-tuned-q4-vulkan-f6-tools-v1": "94847d5bbcaae51a3cf63c6aed0063922e008521267d4ccdd364bf8533e85050",
    "gemma-mixed48-gpu-mobileragplus-energy-v3": "63a7374c71b404eb4f8cf8322beca2bc862061cba29adb368131c73e198f59e1",
    "gemma-mixed48-gpu-f6-energy-v3": "63a7374c71b404eb4f8cf8322beca2bc862061cba29adb368131c73e198f59e1",
    "gemma-base-mixed248-gpu-mobileragplus-flag-v3": "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
    "gemma-base-mixed248-gpu-f6-flag-v3": "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
    "lfm3-tuned-q4km-vulkan-mobileragplus-lfm-v3": "487c5a5530a599acc1827fffbf1b1fa17a5cdbe2f31082632fa3c572bdfcc8f3",
    "lfm3-tuned-q4km-vulkan-f6-lfm-v3": "487c5a5530a599acc1827fffbf1b1fa17a5cdbe2f31082632fa3c572bdfcc8f3",
    "lfm3-rlvr-v3s400-q4-vulkan-mobileragplus-lfm-v3": "689735f30a195be407fc56c5c4b7b27e3043c37b5ff2e58480477cda5992d6d8",
    "lfm3-rlvr-v3s400-q4-vulkan-f6-lfm-v3": "689735f30a195be407fc56c5c4b7b27e3043c37b5ff2e58480477cda5992d6d8",
}
CONFIGURATION_FIXTURES = {
    configuration: V3_FIXTURE_SHA256 if configuration.endswith("-v3") else TOOL_FIXTURE_SHA256 if configuration.endswith("-tools-v1") else FIXTURE_SHA256
    for configuration in CONFIGURATION_MODELS
}

# Stable wire IDs: preserve historical batch labels for existing sessions and APKs.
BATCH_NAMES = {
    1: "Batch 1 · Previously tested GPUs",
    2: "Batch 2 · New LiteRT GPUs",
    3: "Batch 3 · Original Gemma",
    4: "Batch 4 · LFM3 CPU",
    5: "Batch 5 · Energy CPU",
    101: "Config 1 · Energy mixed 4/8-bit GPU",
    102: "Config 2 · LFM3 Q4_K_M Vulkan GPU",
    103: "Config 3 · LFM3 LiteRT GPU",
    104: "Config 4 · Gemma 4 E2B-IT mobile GPU",
    105: "Config 5 · Gemma 4 E2B-IT Q8 Vulkan GPU",
    106: "Config 6 · LFM3 Q4_K_M CPU",
    107: "Config 7 · Energy int8 CPU",
    108: "Config 8 · Energy mixed 4/8-bit CPU",
    109: "Config 9 · Gemma 4 E2B-IT mobile CPU",
    110: "Case A · LFM3 base Q4_K_M · MobileRAG+ GPU",
    111: "Case B · LFM3 tuned Q4_K_M · MobileRAG+ GPU",
    112: "Case C · LFM3 base Q4_K_M · F6 GPU",
    113: "Case D · LFM3 tuned Q4_K_M · F6 GPU",
    114: "Case E · LFM3 tuned Q4_0 · MobileRAG+ GPU",
    115: "Case F · LFM3 tuned Q4_0 · F6 GPU",
    116: "Config 17 · ENERGY · mobileragplus · v3",
    117: "Config 18 · ENERGY · f6 · v3",
    118: "Config 19 · FLAG · mobileragplus · v3",
    119: "Config 20 · FLAG · f6 · v3",
    120: "Config 21 · LFM · mobileragplus · v3",
    121: "Config 22 · LFM · f6 · v3",
    122: "Config 23 · LFM · mobileragplus · v3",
    123: "Config 24 · LFM · f6 · v3",
}

# Session `batch` -> configurations that must all reach 120 completed questions before HF publication.
# Profiles 101..109 run exactly one configuration (CONFIGURATION_MODELS order); legacy groups 1..5
# keep the pre-split Android BATCH_CONFIG_IDS.
_IDS = list(CONFIGURATION_MODELS)
BATCH_CONFIGURATIONS = {
    1: (_IDS[0], _IDS[1]),
    2: (_IDS[2], _IDS[3]),
    3: (_IDS[4], _IDS[8]),
    4: (_IDS[5],),
    5: (_IDS[6], _IDS[7]),
    **{101 + index: (configuration,) for index, configuration in enumerate(_IDS)},
}

# Fixed members written by ReportExporter.archive(); raw native logs are matched separately.
FIXED_MEMBERS = {
    "report.json",
    "summary.txt",
    "rag-replay.json",
    "comparisons.jsonl",
    "answers-and-context.txt",
    "native-provenance.json",
    "lfm3-chat-template.jinja",
    "gemma4-chat-template.jinja",
}
LOG_MEMBER = re.compile(r"[A-Za-z0-9_.-]{1,128}\.(log|trace)")

# Queue states after which the phone stops producing checkpoints for that run.
TERMINAL_QUEUE_STATES = {"completed", "stopped", "error"}
