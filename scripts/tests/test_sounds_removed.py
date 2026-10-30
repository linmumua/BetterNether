"""用户移除自定义声音后，不能因为正常生成再次把音频带进包里。"""

import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from bnlib import biomes, sounds


def test_sound_hook_is_explicitly_disabled_and_writes_nothing(tmp_path):
    upstream = tmp_path / "upstream"
    root = tmp_path / "pack"
    root.mkdir()
    # 连上游目录都不需要：禁用阶段不能继续依赖其声音清单或读取音频。
    report = sounds.generate(upstream, root, ["betternether:anchor_tree_log"])
    assert report["disabled"] is True
    assert report["files"] == report["events"] == report["sound_files"] == 0
    assert report["written"] == [] and report["sound_entries"] == {}
    assert list(root.rglob("*")) == []


def test_carrier_definitions_do_not_schedule_unpublished_custom_events():
    # Attribute 的静态初始化需要真实 Paper 注册表；这里只核查发布定义，不伪造服务端环境。
    root = Path(__file__).resolve().parents[2]
    source = (root / "common/src/main/java/dev/mumu/betternether/entity/CarrierSpecies.java").read_text(encoding="utf-8")
    definitions = re.findall(
        r'\b([A-Z_]+)\("[^"]+", EntityType\.[A-Z_]+, (?:true|false), (?:true|false),\s*([^,\n]+),',
        source,
    )
    assert len(definitions) == 6, "六种已移植物种都必须被检查，防止空匹配误绿"
    assert all(sound.strip() == "null" for _, sound in definitions), definitions


def test_biome_audio_does_not_reference_removed_sound_events():
    original = "minecraft:ambient.nether_wastes.loop"
    raw = {"attributes": {
        "minecraft:audio/ambient_sounds": {"argument": {
            "loop": "betternether:betternether.ambient.nether_jungle",
            "unknown": "betternether:removed.sound",
            "vanilla": original,
        }},
    }}
    result, count = biomes.substitute_mod_sounds(raw)
    audio = result["attributes"]["minecraft:audio/ambient_sounds"]["argument"]
    assert count == 2
    assert "betternether:" not in json.dumps(audio)
    assert audio["vanilla"] == original
    assert audio["loop"] == "minecraft:ambient.warped_forest.loop"
    assert "unknown" not in audio
