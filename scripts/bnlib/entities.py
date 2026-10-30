#!/usr/bin/env python3
"""上游 6 类生物的可落地替代：把 BetterNether 的自定义生物映射到**原版 carrier**，并守住这条边界。

## 为什么不是"生成 CE entity YAML"

CraftEngine 26.10 确实有 `entity:` 段，但它的真实 schema（读自安装包 jar 字节码，不是文档站）是：

  * `AbstractEntityManager$EntityParser.CONFIG_SECTION_NAME = ConfigKeys.of("entit(y|ies)")`
    —— 段名 `entity:` / `entities:` 都收，`type()` 返回 `entity`。
  * `parseSection` 只做一件事：`new EntityDefinition(id, EntitySettings.fromConfig(section.getSection("settings")))`。
  * `EntitySettingsModifiers` 只注册了两个 modifier：`attributes`（属性 -> double）与 `tags`（标签）。
    写第三个键会抛 `KnownResourceException`。
  * `EntityDefinition` 的字段只有 `id` 和 `settings` —— **没有** carrier/`type`、碰撞箱、AI、模型、
    生成规则、掉落表。
  * 实体 id 由 `EntityProvider`（`plugin()` / `spawnEntity(...)` / `getEntityId(...)`）供给，
    即 CE 只给"已存在的实体"挂属性与标签，自己不产生新 EntityType。

所以：写一份带 `type: minecraft:allay`、`hitbox:`、`ai:` 的 CE 配置会直接加载失败；
写一份合法的 `settings.attributes` 而没有 provider 提供 `betternether:firefly` 这个 id，则完全无效。
两条都不做 —— 本模块**不向 ce_root 写任何 entity 配置**，只做上游覆盖校验并交出映射报告，
运行期由 `common/src/main/java/dev/mumu/betternether/entity/CarrierSpecies.java` 用原版载体落地。

## 6 类生物的载体选择依据

维度取自上游 `NetherEntities.register(...)` 的 `width/height`，属性取自各 `createMobAttributes()`。
选择原则：先看**碰撞箱与移动方式**（飞行/地面），再看**下界出身与火焰免疫**，最后才看外观；
外观一律由载体原版模型决定，这里不假装一致。

## 硬限制（真实做不到，不是没做）

  * 新 EntityType / 自定义 AI / 自定义模型与动画：Paper 与 CE 都不提供实体类型注册。
  * 上游自定义粒子 type（`betternether:*` particle）：CE 只能引用原版粒子。
  * 自然生成（群系 `spawners` 里的 betternether 实体）：CE entity 段没有生成规则；
    且按需求"玩家自定义前不把默认世界刷满新生物"，本阶段一律不自动生成。
  * 实体掉落表：上游 `loot_table/entities/*` 绑定的是自定义实体，载体是原版实体，掉落需另做。
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import Sequence

# 上游注册表：6 个生物 + 1 个投射物都在这个文件里。
REGISTRY = "src/main/java/org/betterx/betternether/registry/NetherEntities.java"

# 只认 Mob 注册；`naga_projectile` 是投射物（`registerEntity`），不是生物。
NON_MOB_IDS = {"naga_projectile"}

# 上游 id -> 载体方案。数值是上游真实属性，不是估计值。
#
# carrier 是原版 EntityType 名（与 Java 侧 CarrierSpecies 必须一致）；
# attributes 只列上游真的设置过的项；note 记录"可感知替代"的取舍。
CARRIERS: dict[str, dict[str, object]] = {
    "firefly": {
        "carrier": "ALLAY",
        "size": (0.5, 0.5),
        "upstream_category": "AMBIENT",
        "attributes": {"MAX_HEALTH": 1.0, "MOVEMENT_SPEED": 0.25, "FOLLOW_RANGE": 48.0},
        "glowing": True,
        "ambient_sound": "betternether:betternether.mob.firefly.fly",
        "note": "0.5x0.5 的飞行小体 + 发光，ALLAY 的碰撞箱与飞行姿态最接近；"
                "外观仍是悦灵，不冒充萤火虫模型",
    },
    "hydrogen_jellyfish": {
        "carrier": "GHAST",
        "size": (2.0, 5.0),
        "upstream_category": "AMBIENT",
        "attributes": {"MAX_HEALTH": 0.5, "MOVEMENT_SPEED": 0.5, "ATTACK_DAMAGE": 20.0},
        "glowing": True,
        "ambient_sound": "betternether:betternether.mob.jellyfish",
        "note": "2x5 的大体、漂浮、下界出身、火焰免疫，GHAST 四项全中；"
                "代价是它会发射火球，与'温顺水母'不同",
    },
    "naga": {
        "carrier": "BLAZE",
        "size": (0.625, 2.75),
        "upstream_category": "MONSTER",
        "attributes": {"MAX_HEALTH": 10.0, "FOLLOW_RANGE": 35.0,
                       "MOVEMENT_SPEED": 0.23, "ATTACK_DAMAGE": 3.0},
        "glowing": False,
        "ambient_sound": "betternether:betternether.mob.naga_idle",
        "note": "上游是远程攻击者（RangedAttackGoal）；BLAZE 同为下界远程、火焰免疫、"
                "碰撞箱接近，但投射物是火球而非 naga_projectile",
    },
    "flying_pig": {
        "carrier": "PIG",
        "size": (1.0, 1.25),
        "upstream_category": "AMBIENT",
        "attributes": {"MAX_HEALTH": 6.0, "FOLLOW_RANGE": 12.0,
                       "MOVEMENT_SPEED": 0.3, "ATTACK_DAMAGE": 3.0},
        "glowing": False,
        "ambient_sound": None,
        "note": "只保住'猪'这个身份与体型；飞行本身没有载体（原版没有会飞的猪），"
                "不额外加飘浮效果——那会让它卡在天花板上，比不会飞更糟",
    },
    "jungle_skeleton": {
        "carrier": "BOGGED",
        "size": (0.6, 1.99),
        "upstream_category": "MONSTER",
        "attributes": {"MAX_HEALTH": 20.0, "MOVEMENT_SPEED": 0.25},
        "glowing": False,
        "ambient_sound": None,
        "note": "上游直接继承原版 Skeleton（含弓箭 AI 与装备生成）；BOGGED 是骷髅家族里"
                "外观最有辨识度的变体，碰撞箱 0.6x1.99 一致",
    },
    "skull": {
        "carrier": "VEX",
        "size": (0.625, 0.625),
        "upstream_category": "MONSTER",
        "attributes": {"MAX_HEALTH": 4.0, "FOLLOW_RANGE": 20.0,
                       "MOVEMENT_SPEED": 0.5, "ATTACK_DAMAGE": 1.0},
        "glowing": False,
        "ambient_sound": "betternether:betternether.mob.skull_flight",
        "note": "飞行的小型敌对单位，VEX 是原版唯一同形态载体；"
                "VEX 持剑近战，上游骷髅头是撞击伤害",
    },
}

_REGISTER_RE = re.compile(r'register\(\s*"([a-z0-9_]+)"')


def _upstream_mob_ids(registry: Path) -> list[str]:
    """从上游注册表里读出真正注册的 Mob id。

    用 `register("name"` 这个固定形状，而不是类名：类名可以改，注册 id 是对外契约。
    """
    if not registry.is_file():
        raise FileNotFoundError(f"找不到上游实体注册表：{registry}")
    text = registry.read_text(encoding="utf-8")
    found = {match.group(1) for match in _REGISTER_RE.finditer(text)}
    return sorted(found - NON_MOB_IDS)


def _java_ids(entity_dir: Path) -> set[str] | None:
    """从 Java 载体表里读出上游 id 字面量，用于跨语言漂移检查。

    找不到 Java 文件时返回 None（允许本模块先于 Java 侧单独跑）。
    """
    source = entity_dir / "CarrierSpecies.java"
    if not source.is_file():
        return None
    text = source.read_text(encoding="utf-8")
    return set(re.findall(r'"([a-z0-9_]+)"', text))


def generate(
    upstream: Path,
    ce_root: Path,
    selected: Sequence[str] = (),
) -> dict[str, object]:
    """校验上游 6 类生物都被覆盖，交出载体映射；**不写 ce_root**（原因见模块文档）。

    `selected` 是集成器的清单过滤参数；实体映射不是方块/物品清单，这里忽略它。

    幂等：纯只读校验，不产生文件。失败要响：上游新增/删除生物导致映射表失配时抛异常。
    """
    del ce_root  # 明确：本模块不写盘，参数保留只为统一签名。
    del selected

    registry = upstream / REGISTRY
    upstream_ids = _upstream_mob_ids(registry)
    mapped = set(CARRIERS)

    missing = sorted(set(upstream_ids) - mapped)
    stale = sorted(mapped - set(upstream_ids))
    if missing or stale:
        raise ValueError(
            "上游生物集合与载体映射不一致，拒绝静默放过："
            f"未映射={missing}，上游已不存在={stale}。"
            "请同步更新 CARRIERS 与 Java 侧 CarrierSpecies")

    java_dir = Path(__file__).resolve().parents[2] / "common/src/main/java/dev/mumu/betternether/entity"
    java_ids = _java_ids(java_dir)
    warnings: list[str] = []
    if java_ids is not None:
        absent = sorted(mapped - java_ids)
        if absent:
            raise ValueError(f"Java 载体表缺少上游 id {absent}，两侧映射已漂移")

    unsupported = [
        "新 EntityType / 自定义 AI：Paper 与 CraftEngine 都不提供实体类型注册，"
        "CE 的 entity 段只接受 settings.attributes 与 settings.tags（无 carrier/碰撞箱/模型字段）",
        "自定义模型与骨骼动画：载体沿用原版模型，上游 ModelEntity* 不复刻",
        "上游自定义粒子 type（betternether:*）：CE 粒子只能引用原版粒子",
        "群系自然生成：CE entity 段无生成规则；按需求在玩家自定义前不向默认世界生成任何新生物",
        "实体掉落表（loot_table/entities/*）：绑定的是自定义实体，载体为原版实体，需另做掉落方案",
        "火焰免疫：上游全部 fireImmune，载体中 GHAST/BLAZE 天然免疫，"
        "其余载体由 Java 侧用抗火效果近似（可感知替代，不是同一机制）",
    ]

    carriers = {
        upstream_id: {
            "carrier": spec["carrier"],
            "size": list(spec["size"]),
            "upstream_category": spec["upstream_category"],
            "attributes": spec["attributes"],
            "glowing": spec["glowing"],
            "ambient_sound": spec["ambient_sound"],
            "note": spec["note"],
        }
        for upstream_id, spec in CARRIERS.items()
    }

    return {
        "files": 0,
        "written": [],
        "warnings": warnings,
        "unsupported": unsupported,
        "carriers": carriers,
        "upstream_ids": upstream_ids,
        "ce_entity_yaml_written": 0,
    }
