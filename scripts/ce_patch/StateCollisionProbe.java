/*
 * 逐「状态」粒度取证：每个原版方块状态下，碰撞箱是否为空、是否遮挡。
 *
 * 为什么需要它：VanillaBlockStates 只报默认状态的碰撞箱。CE 的 mappings.yml
 * 是按「单个状态」借出去的，一个方块里可能有 60 个状态无碰撞、3 个有碰撞
 * （例如 tripwire 的 attached=true 与 sculk_vein 的 waterlogged=true）。
 * 只按默认状态判断，会把有碰撞的状态当成可用槽位。
 *
 * 输出 collstates.tsv，列：block / state / collEmpty / canOcclude / collBoxCount
 *
 * 用法（Git Bash）：
 *   JAR="E:/我的世界插件/folia测试端/versions/26.3/mucore-26.3.jar"
 *   LIBS=$(find "E:/我的世界插件/folia测试端/libraries" -name '*.jar' | tr '\n' ';')
 *   javac -nowarn -cp "$JAR;$LIBS" -d . StateCollisionProbe.java
 *   java -cp ".;$JAR;$LIBS" StateCollisionProbe
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

public class StateCollisionProbe {
    private static final Set<String> FOCUS = new HashSet<>(Arrays.asList(
        "tripwire", "sculk_vein", "resin_clump", "cactus", "kelp", "kelp_plant",
        "sugar_cane", "weeping_vines", "weeping_vines_plant", "twisting_vines",
        "twisting_vines_plant", "cave_vines", "cave_vines_plant", "vine",
        // PRESSURE_PLATE 组绑这两个：如果碰撞只有 1/16 高且不遮挡，就是植物载体的
        // 最后一个可用池（tripwire/vine 系已被别的包占满）。
        "light_weighted_pressure_plate", "heavy_weighted_pressure_plate",
        "dead_brain_coral", "dead_brain_coral_fan", "dead_brain_coral_wall_fan",
        "dead_bubble_coral", "dead_bubble_coral_fan", "dead_bubble_coral_wall_fan",
        "dead_fire_coral", "dead_fire_coral_fan", "dead_fire_coral_wall_fan",
        "dead_horn_coral", "dead_horn_coral_fan", "dead_horn_coral_wall_fan",
        "dead_tube_coral", "dead_tube_coral_fan", "dead_tube_coral_wall_fan"
    ));

    public static void main(String[] a) throws Exception {
        List<String> out = new ArrayList<>();
        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        out.add("block\tstate\tcollEmpty\tcanOcclude\tboxes");
        for (Block b : BuiltInRegistries.BLOCK) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(b);
            if (id == null || !"minecraft".equals(id.getNamespace())) continue;
            if (!FOCUS.contains(id.getPath())) continue;
            for (BlockState st : b.getStateDefinition().getPossibleStates()) {
                String coll;
                int boxes;
                try {
                    var shape = st.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                    coll = String.valueOf(shape.isEmpty());
                    boxes = shape.toAabbs().size();
                } catch (Throwable t) {
                    coll = "err";
                    boxes = -1;
                }
                out.add(id.getPath() + "\t" + st.toString() + "\t" + coll + "\t"
                        + st.canOcclude() + "\t" + boxes);
            }
        }
        Files.write(Paths.get("collstates.tsv"), out);
    }
}
