"""非完整方块家具化：把 CE 表达不了的异形家具/座椅改成 `furniture_item`。

## 为什么这些方块必须走家具，而不能继续当方块

CE 的方块**没有独立碰撞箱**：碰撞箱永远是载体原版状态的碰撞箱（见 `blocks.py`
里 pinned-state 一节）。一张 10/16 宽、5/8 高的椅子在方块形态下只能二选一：

  * 借纤细池 → 碰撞变成一根 10/16 宽的全高隐形柱（人站在椅子上，椅子却像墙）；
  * 借满格池 → 碰撞变成 1×1×1（撞到看不见的空气）。

家具不一样：`furniture_item` 的 hitbox 是**实体**（`type: shulker`），几何由
`position` / `scale` / `peek` / `direction` 直接给出，不占任何原版方块状态额度，
而且同一个家具能带多个 hitbox + 座椅。这正是本模块存在的理由。

## shulker hitbox 的几何（从 CE 字节码实测，不是文档推测）

`ShulkerFurnitureHitboxConfig#createAABB`（26.10-SNAPSHOT，`javap -c` 逐条读）：

    physicalPeek(p) = 0.5 − 0.5·sin((p + 0.5)·π) = 0.5 − 0.5·cos(p·π)     p = peek/100
    minX = −scale/2, maxX = +scale/2
    minY = 0,        maxY = scale
    minZ = −scale/2, maxZ = +scale/2
    沿 direction 的轴再延长 physicalPeek·scale（正方向加在 max 上，负方向加在 min 上）
    最后整体平移 (position.x, position.y, position.z)（position 由家具的 yRot 旋转）

所以**一个 shulker 能精确表达**的是「水平是 scale×scale 正方形、竖直在
scale … 2·scale 之间」的长方体 —— 水平两轴永远等长，竖直永远 ≥ scale。
这个约束决定了下面每一族的取舍（逐族写在 `FURNITURE_FAMILIES` 的 `note` 里）。

反解公式（`peek_for`）：给定正方形边长 s 与高度 h，

    h = s·(1 + physicalPeek)  ⇒  physicalPeek = h/s − 1  ⇒  peek = 100·acos(1 − 2·(h/s − 1))/π

`h < s` 或 `h > 2s` 时无解 —— 这一族要么换 `scale`（牺牲水平尺寸），要么拆成多个
hitbox 拼（见火盆：2×2 共 4 个），要么判定「家具也表达不了」（见 geyser）。

## 座椅（chair / taburet / bar_stool）已整体删除

用户 2026-10-30 明确要求「凳子也删了」，因此 69 个座椅不再发布：
`SEAT_BLOCKS` / `SEAT_CLOTH` / `SEAT_BOXES` / `seat_model()` / `model_registry()`
以及 `_auto_uv`（只为座椅合成模型服务）全部移除。
剩下发布的只有火盆 / 基座 / 罐三族，模型直接用上游同名文件。
"""

from __future__ import annotations

import json
import math

NAMESPACE = "betternether"

# ---------------------------------------------------------------------------
# shulker hitbox 几何
# ---------------------------------------------------------------------------


def physical_peek(peek: int) -> float:
    """CE 的 peek → 沿 `direction` 的额外高度比例（= 0.5 − 0.5·cos(peek/100·π)）。"""
    return 0.5 - 0.5 * math.cos(math.pi * peek / 100.0)


def peek_for(scale: float, height: float) -> int:
    """反解：要让 `scale` 见方的 hitbox 长到 `height` 高，peek 该给多少（取整）。"""
    wanted = height / scale - 1.0          # 需要的 physicalPeek
    if not (0.0 <= wanted <= 1.0):
        raise ValueError(
            f"scale={scale} 的 shulker 只能长到 {scale}..{2 * scale} 高，给不出 {height}")
    return max(0, min(100, round(math.acos(1.0 - 2.0 * wanted) / math.pi * 100.0)))


def hitbox_height(scale: float, peek: int) -> float:
    """这个 shulker hitbox 实际有多高（用于自检报告里的误差列）。"""
    return scale * (1.0 + physical_peek(peek))


def shulker(scale: float, peek: int = 0, position: tuple[float, float, float] = (0.0, 0.0, 0.0),
            direction: str = "up", seats: tuple[str, ...] = (),
            blocks_building: bool = True, interactive: bool = True) -> dict:
    """一个 shulker hitbox 的描述（渲染成 YAML 见 {@link _hitbox_yaml}）。"""
    return {
        "type": "shulker",
        "position": position,
        "direction": direction,
        "peek": peek,
        "scale": scale,
        "blocks_building": blocks_building,
        "interactive": interactive,
        "invisible": True,
        "seats": seats,
    }


# ---------------------------------------------------------------------------
# 座椅模型（bclib 几何，见模块文档）
# ---------------------------------------------------------------------------
#




# ---------------------------------------------------------------------------
# 家具真值表
# ---------------------------------------------------------------------------
#
# 每一项：
#   name     —— 族名（也用于报告与门禁）
#   blocks   —— 属于这一族的上游方块名（不含命名空间）。显式列出而不是按后缀猜：
#               座椅有 `chair_cincinnasite` 这种后缀在前面的名字，猜后缀会漏。
#   truth    —— 上游 `block_shapes.txt` 的碰撞箱（方块局部 AABB），逐字抄写
#   hitboxes —— shulker hitbox 列表（{@link shulker}）
#   rotation —— `furniture_item` 的 rotation 规则
#   note     —— 为什么这么切分 / 实际误差
#
# 误差口径：`hitbox_height` 与 truth 的差值写进 `note`，并由 `geometry_report()`
# 在每次生成时重算一遍（不靠注释里的手算，防止改了 scale 忘了改注释）。


# **座椅族（chair / taburet / bar_stool，共 69 个）已按用户要求整体删除**：
#   「酿造台也删了凳子也删了」。三个族连同 `SEAT_BLOCKS` / `SEAT_CLOTH` / `seat_model()`
#   一并移除 —— 留着不用的族定义只会让 `FURNITURE_FAMILIES` 与产物对不上。
FURNITURE_FAMILIES: tuple[dict, ...] = (
    {
        "name": "fire_bowl",
        "blocks": ("bricks_fire_bowl", "bricks_fire_bowl_soul", "cincinnasite_fire_bowl",
                   "cincinnasite_fire_bowl_soul", "netherite_fire_bowl",
                   "netherite_fire_bowl_soul"),
        "truth": "AABB[0,0,0]->[1,0.75,1]（整格见方 × 3/4 高）",
        # 1×1 水平 + 0.75 高：单个 shulker 给不出（高度下界是 scale）。拆成 2×2 个
        # scale=0.5 的 shulker，peek=50 ⇒ 每个 0.5 见方、0.5·(1+0.5)=0.75 高，四块并起来
        # 正好 1×0.75×1。
        "hitboxes": [
            shulker(scale=0.5, peek=50, position=(-0.25, 0.0, -0.25)),
            shulker(scale=0.5, peek=50, position=(0.25, 0.0, -0.25)),
            shulker(scale=0.5, peek=50, position=(-0.25, 0.0, 0.25)),
            shulker(scale=0.5, peek=50, position=(0.25, 0.0, 0.25)),
        ],
        "rotation": "eight",
        # 上游没有 `*_fire_bowl.json`，只有 `*_fire_bowl_off/on`；家具发布的是未点燃外观。
        "item_stem": "{name}_off",
        # 模型自身 y 范围 0~0.75（实测 `*_fire_bowl_off.json` 的 elements：from.y 全 0，
        # to.y 最大 12/16）。CE 的物品展示实体把模型**几何中心**对齐到原点，所以要让
        # 模型底边落在方块底面，必须向上平移 `高度 / 2`（官方 bench/wooden_chair 的
        # 1×1×1 模型用的正是 `translation: 0,0.5,0`）。
        "model_height": 0.75,
        "note": "4 个 shulker 拼成 1×0.75×1，与上游逐位相等（每个 0.5 见方 × 0.75 高，"
                "peek=50 ⇒ physicalPeek=0.5）。代价是每个火盆落地要 4 个 hitbox 实体。"
                "**未实现**：上游的 `fire` 状态（点燃/熄灭 + 亮度）。家具变体要配 "
                "`glowing_furniture` 与 events 才能点亮，本次只发布未点燃外观 —— "
                "改法见 furniture.py 的 TODO（variant=lit + flint_and_steel）。",
    },
    {
        "name": "pedestal",
        "blocks": ("cincinnasite_pedestal",),
        "truth": "AABB[0.125,0,0.125]->[0.875,1,0.875]（12/16 见方 × 1 高）",
        # 模型 y 范围 0~1（实测 `cincinnasite_pedestal.json`），平移半格与官方 1×1×1 家具一致。
        "model_height": 1.0,
        # 12/16 见方、1 高：h/s = 1.3333 ⇒ physicalPeek=0.3333 ⇒ peek=39。
        "hitboxes": [shulker(scale=0.75, peek=peek_for(0.75, 1.0))],
        "rotation": "eight",
        "note": "水平精确，高度 0.9960（peek 只能取整，误差 −0.004 格）。"
                "**未实现**：上游是 `BlockCincinnasitePedestal`，能把物品摆在顶面上；"
                "家具形态没有这个交互 —— 这是家具化的固定代价，逐块记在报告里。",
    },
    {
        "name": "pot",
        "blocks": ("brick_pot", "cincinnasite_pot"),
        # 两个 pot 都经 parent 链解析后 y 范围 0~0.5（`brick_pot` → `cincinnasite_pot`），
        # 平移 1/4 格即可让底边落在地面。
        "model_height": 0.5,
        "truth": "AABB[0.1875,0,0.1875]->[0.8125,0.5,0.8125]（10/16 见方 × 1/2 高）",
        # 5/8 见方 × 1/2 高：h < s，任何 scale 都给不出方形底 + 更矮的高度。
        "hitboxes": [shulker(scale=0.5, peek=0)],
        "rotation": "eight",
        "note": "高度精确（0.5），水平 0.5 比上游的 0.625 窄 2/16（每边 1/16）—— 罐子"
                "四周 1/16 的边沿可以走进去。这是「高度优先」的取舍：罐子是矮装饰，"
                "踩空 1/16 看不出来，而高度错了会挡住视线。",
    },
)

#: 快查表：方块名 → 族（`blocks.classify` 用）。
FAMILY_OF: dict[str, dict] = {
    block: family for family in FURNITURE_FAMILIES for block in family["blocks"]
}


def family_of(name: str) -> dict | None:
    """这个名字是不是家具族成员；不是返回 `None`。"""
    return FAMILY_OF.get(name.split(":", 1)[-1])


# ---------------------------------------------------------------------------
# YAML 渲染
# ---------------------------------------------------------------------------


def _num(value: float) -> str:
    """坐标/尺寸的写法：整数不带小数点，小数去掉尾随 0（`0.625` 而不是 `0.625000`）。"""
    text = f"{value:.5f}".rstrip("0").rstrip(".")
    return text or "0"


def _vec(values) -> str:
    return ",".join(_num(float(value)) for value in values)


def _hitbox_yaml(hitbox: dict, indent: str) -> str:
    lines = [
        f"{indent}- type: {hitbox['type']}",
        f"{indent}  position: {_vec(hitbox['position'])}",
        f"{indent}  direction: {hitbox['direction']}",
        f"{indent}  peek: {hitbox['peek']}",
        f"{indent}  scale: {_num(hitbox['scale'])}",
        f"{indent}  blocks_building: {'true' if hitbox['blocks_building'] else 'false'}",
        f"{indent}  interactive: {'true' if hitbox['interactive'] else 'false'}",
        f"{indent}  invisible: {'true' if hitbox['invisible'] else 'false'}",
    ]
    if hitbox["seats"]:
        lines.append(f"{indent}  seats:")
        lines.extend(f"{indent}    - {seat}" for seat in hitbox["seats"])
    return "".join(f"{line}\n" for line in lines)


def model_path_of(name: str, family: dict) -> str:
    """家具元素引用的模型路径。

    直接用上游自己的模型 —— 但**模型名不一定等于方块名**：火盆的上游
    blockstate 只有 `*_fire_bowl_off` / `*_fire_bowl_on` 两个模型，没有同名的
    `*_fire_bowl.json`，所以族里用 `item_stem` 显式给出模型名模板。
    """
    return f"{NAMESPACE}:block/{family.get('item_stem', '{name}').format(name=name)}"


def furniture_item_yaml(block_id: str, family: dict, *,
                        item_name: str, sounds: dict[str, str],
                        hit_times: int = 3) -> str:
    """一段家具 `items:` 定义（`behavior: furniture_item` + 一个 ground 变体）。

    `sounds` 由调用方从上游 SoundType 取（`blocks.sound_events`），只保留家具
    settings 认的三个事件：break / place / hit。
    """
    name = block_id.split(":", 1)[1]
    model = model_path_of(name, family)
    rotation = family["rotation"]
    template = ("default:rotatable_furniture_4" if rotation == "four"
                else "default:rotatable_furniture_8")
    hitboxes = "".join(_hitbox_yaml(hitbox, "              ") for hitbox in family["hitboxes"])
    # 座椅的掉落物要从座面上方弹出（与官方家具一致），其余族用默认偏移。
    spawn = "0,0.25,0"
    return (
        f"  {block_id}:\n"
        f"    data:\n"
        f"      item_name: '{item_name}'\n"
        f"    model:\n"
        f"      path: {model}\n"
        f"    behavior:\n"
        f"      type: furniture_item\n"
        f"      rules:\n"
        f"        ground:\n"
        f"          rotation: {rotation}\n"
        f"          alignment: center\n"
        f"      furniture:\n"
        f"        events:\n"
        f"          - template: {template}\n"
        f"        settings:\n"
        f"          item: {block_id}\n"
        f"          hit_times: {hit_times}\n"
        f"          sounds:\n"
        f"            break: {sounds['break']}\n"
        f"            place: {sounds['place']}\n"
        f"            hit: {sounds['hit']}\n"
        f"        variants:\n"
        f"          ground:\n"
        f"            loot_spawn_offset: {spawn}\n"
        f"            elements:\n"
        f"              - item: {block_id}\n"
        # CE 的物品展示实体把模型**几何中心**对齐到家具原点，所以任何底边贴地的模型
        # 都会下沉「自身高度的一半」。官方 `default:bench` / `wooden_chair`（1×1×1）
        # 就是用 `translation: 0,0.5,0` 抵掉的。这里按族的真实模型高度算，不写死 0.5：
        # 火盆 0.75 → 0.375、基座 1.0 → 0.5、陶罐 0.5 → 0.25。
        # 不写死是因为写错会让家具一半埋进地里（用户 2026-10-31 实测「火盆卡在地下」）。
        f"                translation: 0,{family['model_height'] / 2:g},0\n"
        f"                display_transform: none\n"
        f"                billboard: fixed\n"
        f"                shadow_radius: 0.4\n"
        f"                shadow_strength: 0.5\n"
        f"            hitboxes:\n"
        f"{hitboxes}"
        f"        loot:\n"
        f"          template: default:loot_table/furniture\n"
        f"          arguments:\n"
        f"            item: {block_id}\n"
    )


# ---------------------------------------------------------------------------
# 自检 / 报告
# ---------------------------------------------------------------------------


def geometry_report() -> list[dict]:
    """逐族把「上游碰撞箱 vs hitbox 实际覆盖的并集」算出来（每次生成都重算）。

    只算包围盒级别的误差（并集 AABB），因为报告要让门禁能断言「没人悄悄写了一个
    明显错的碰撞箱」；逐 hitbox 的原始参数另列在 `hitboxes` 里。
    """
    report = []
    for family in FURNITURE_FAMILIES:
        boxes = []
        for hitbox in family["hitboxes"]:
            scale = hitbox["scale"]
            height = hitbox_height(scale, hitbox["peek"])
            px, py, pz = hitbox["position"]
            boxes.append((px - scale / 2, py, pz - scale / 2,
                          px + scale / 2, py + height, pz + scale / 2))
        lo = [min(box[i] for box in boxes) for i in range(3)]
        hi = [max(box[i + 3] for box in boxes) for i in range(3)]
        report.append({
            "family": family["name"],
            "blocks": len(family["blocks"]),
            "truth": family["truth"],
            "hitboxes": len(boxes),
            "union": [round(value, 5) for value in lo + hi],
            "note": family["note"],
        })
    return report


def dump_report(path: str) -> None:
    """把逐族几何事实写成 JSON（供门禁与人工核对）。"""
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(json.dumps({"families": geometry_report()},
                                ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":       # 手工检查：python -m bnlib.furniture
    for row in geometry_report():
        print(f"{row['family']:12s} {row['blocks']:3d} 个  hitboxes={row['hitboxes']}  "
              f"union={row['union']}")
        print(f"             {row['truth']}")
