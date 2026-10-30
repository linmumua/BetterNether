package dev.mumu.betternether.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * {@link Layers#emit} 的 {@code min} 边界：与上游两种 find 循环逐字一致。
 *
 * <pre>
 * // 地面（findOnGroundYPosition）
 * for (int y = startY; y &gt;= minHeight + 1; --y) { … emit(y); }
 * // 天花板（findUnderGroundYPosition）
 * for (int y = startY; y &gt;= minHeight + 1; --y) { … emit(y - 1); }
 * </pre>
 *
 * <p>两个循环的下界都是 {@code minHeight + 1}，差别只在发射时是否 `-1`。所以同一个
 * {@code min=4} 参数下：地面模式最低能发 <b>5</b>，天花板模式最低能发 <b>4</b>。
 * 这条不对称是上游行为，不能为了「看起来对称」把它抹平（抹平就会多/少一整层）。
 *
 * <p>边界值用裸 {@link Layers#layers}（收集版）断言：生产路径是 {@code emit} + consumer，
 * 二者共用同一段扫描，收集版只是把 consumer 换成 {@code List::add}。
 */
class LayersMinBoundsTest {
    private static final int MIN_Y = 0;
    private static final int MAX_Y = 127;

    /** 只放一块地形，其余空气。 */
    private static FakeWorld withTerrainAt(int y, Material material) {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 1L);
        world.solid(0, y, 0, material);
        return world;
    }

    @Test
    void groundModeLowestEmittedLayerIsMinPlusOne() {
        // min=4，地形在 y=4（非基岩）→ 地面层只有 y=5：循环下界是 min+1=5。
        FakeWorld world = withTerrainAt(4, Material.STONE);
        List<Point> out = new ArrayList<>();
        Layers.layers(world, 0, 0, 4, MAX_Y, true, out);

        assertEquals(List.of(new Point(0, 5, 0)), out,
                "地面模式最低可发层是 min+1=5（循环条件 y >= minHeight + 1），不能发到 4");
    }

    @Test
    void ceilingModeLowestEmittedLayerIsMin() {
        // min=4，地形在 y=5、其下 y=4 是空气 → 天花板层发 y-1=4：循环下界 5，发射 -1 后就是 4。
        FakeWorld world = withTerrainAt(5, Material.STONE);
        List<Point> out = new ArrayList<>();
        Layers.layers(world, 0, 0, 4, MAX_Y, false, out);

        assertEquals(List.of(new Point(0, 4, 0)), out,
                "天花板模式最低可发层是 min=4（循环 y>=min+1、发射 y-1），不能发到 3");
    }

    @Test
    void neitherModeEmitsBelowMin() {
        // 地形铺满 y=1..3：都低于 min=4，两种模式都不许发射（地面最低 5、天花板最低 4）。
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 1L);
        for (int y = 1; y <= 3; y++) {
            world.solid(0, y, 0, Material.STONE);
        }
        List<Point> ground = new ArrayList<>();
        Layers.layers(world, 0, 0, 4, MAX_Y, true, ground);
        List<Point> ceiling = new ArrayList<>();
        Layers.layers(world, 0, 0, 4, MAX_Y, false, ceiling);

        assertFalse(ground.contains(new Point(0, 4, 0)),
                "地面模式不许发 min=4 那一层：" + ground);
        assertTrue(ceiling.isEmpty(), "天花板模式在 min 以下没有可发层：" + ceiling);
    }

    /**
     * 床顶（{@code bedrock}）排除：基岩正下方/正上方都不算层。
     *
     * <p>上游同时要求 {@code !pos.is(BEDROCK)}（天花板模式）与 {@code !pos.below().is(BEDROCK)}
     * （地面模式）。地板板是基岩，靠这条把「床顶」排除掉——不是靠 {@code minY + 1} 的偏移。
     */
    @Test
    void bedrockIsNeverALayerFloorOrCeiling() {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 1L);
        world.solid(0, 40, 0, Material.BEDROCK);   // 基岩地板
        world.solid(0, 60, 0, Material.BEDROCK);   // 基岩天花板

        List<Point> ground = new ArrayList<>();
        Layers.layers(world, 0, 0, MIN_Y, MAX_Y, true, ground);
        assertEquals(List.of(), ground, "基岩上方不该被当成地面层：" + ground);

        List<Point> ceiling = new ArrayList<>();
        Layers.layers(world, 0, 0, MIN_Y, MAX_Y, false, ceiling);
        assertEquals(List.of(), ceiling, "基岩下方不该被当成天花板层：" + ceiling);
    }
}
