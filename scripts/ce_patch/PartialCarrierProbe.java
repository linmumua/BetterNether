/*
 * 取证：找出「碰撞箱非满格、且不遮挡」的原版状态，作为细柱类植物的候选载体。
 *
 * 背景：空碰撞载体（tripwire/sculk_vein 等）会让细柱状方块（蘑菇柄、树干）
 * 失去碰撞，变成可以穿过去的方块。要找的是碰撞箱存在但小于整格、同时
 * canOcclude=false 的方块 —— 那类既能保住「撞得到」的手感，又不会遮挡剔除。
 *
 * 输出 partialcarriers.tsv，列：block / states / partialStates / occludingStates / sampleShape
 *   partialStates = 碰撞非空、bbox 不是满格 (16,16,16)、canOcclude=false 的状态数
 *
 * 用法同 StateCollisionProbe。
 */

import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import java.util.*;
import java.nio.file.*;

public class PartialCarrierProbe {
    private static final double EPS = 1e-6;

    public static void main(String[] a) throws Exception {
        List<String> out = new ArrayList<>();
        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        out.add("block\tstates\tpartialStates\toccludingStates\tlight\tsampleShape");
        for (Block b : BuiltInRegistries.BLOCK) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(b);
            if (id == null || !"minecraft".equals(id.getNamespace())) continue;
            int partial = 0, occ = 0, light = 0;
            String sample = "";
            for (BlockState st : b.getStateDefinition().getPossibleStates()) {
                light = Math.max(light, st.getLightEmission());
                if (st.canOcclude()) { occ++; continue; }
                try {
                    var shape = st.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                    if (shape.isEmpty()) continue;
                    var boxes = shape.toAabbs();
                    boolean full = boxes.size() == 1
                            && boxes.get(0).minX <= EPS && boxes.get(0).minY <= EPS && boxes.get(0).minZ <= EPS
                            && boxes.get(0).maxX >= 1 - EPS && boxes.get(0).maxY >= 1 - EPS && boxes.get(0).maxZ >= 1 - EPS;
                    if (full) continue;
                    partial++;
                    if (sample.isEmpty()) sample = boxes.get(0).toString();
                } catch (Throwable t) {
                    // 保守：判不了就不算候选
                }
            }
            if (partial > 0) {
                out.add(id.getPath() + "\t" + b.getStateDefinition().getPossibleStates().size()
                        + "\t" + partial + "\t" + occ + "\t" + light + "\t" + sample);
            }
        }
        Files.write(Paths.get("partialcarriers.tsv"), out);
    }
}
