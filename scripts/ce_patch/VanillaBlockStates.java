/*
 * 从服务端 jar 里反射出「每个原版方块的真实状态数与碰撞/遮挡/发光」，
 * 用来给 mappings_plant_pools.py 里的 BLOCK_FACTS 做取证。
 *
 * 为什么需要它：CraftEngine 的 mappings.yml 只写「把哪个状态借出去」，
 * 却不说这个方块一共有多少状态、有没有碰撞、会不会发光。仅凭名字推断会错：
 *   - kelp_plant / weeping_vines_plant / twisting_vines_plant 看着像 26 个 age 状态，
 *     实际 GrowingPlantBodyBlock 没有任何 blockstate 属性，只有 1 个状态；
 *   - chorus_plant / *_leaves 不遮挡，但碰撞是满格的；
 *   - glow_lichen 不遮挡无碰撞，但会发光 7。
 *
 * 用法（Windows，Git Bash 下路径要引号）：
 *   JAR="E:/我的世界插件/folia测试端/versions/26.3/mucore-26.3.jar"
 *   LIBS=$(find "E:/我的世界插件/folia测试端/libraries" -name '*.jar' | tr '\n' ';')
 *   javac -nowarn -cp "$JAR;$LIBS" -d . VanillaBlockStates.java
 *   java -cp ".;$JAR;$LIBS" VanillaBlockStates
 *   # 结果写到当前目录 allstates.tsv
 *
 * 关键实现点：必须先 SharedConstants.tryDetectVersion() 再 Bootstrap.bootStrap()，
 * 否则 Blocks 的静态初始化会在 DataFixers / Items 上抛 IllegalStateException。
 * 另外 SolidDebugger 会改写 System.out，所以结果一律写文件，不要指望 stdout。
 *
 * 列含义：
 *   states      状态总数
 *   collEmpty   默认状态的碰撞形状是否为空（true = 可以穿过去）
 *   canOcclude  是否遮挡邻面 / 参与光照遮挡（true = 会挖掉邻居的面）
 *   light       该方块所有状态里的最大 lightEmission
 *   randomTick  默认状态是否随机刻
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

public class VanillaBlockStates {
    public static void main(String[] a) throws Exception {
        List<String> out = new ArrayList<>();
        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        out.add("name\tstates\tcollEmpty\tcanOcclude\tlight\trandomTick\tprops");
        for (Block b : BuiltInRegistries.BLOCK) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(b);
            if (id == null || !"minecraft".equals(id.getNamespace())) continue;
            BlockState s = b.defaultBlockState();
            boolean empty;
            try {
                empty = s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
            } catch (Throwable t) {
                empty = false;
            }
            int light = 0;
            for (BlockState st : b.getStateDefinition().getPossibleStates()) {
                light = Math.max(light, st.getLightEmission());
            }
            out.add(id.getPath() + "\t" + b.getStateDefinition().getPossibleStates().size()
                    + "\t" + empty + "\t" + s.canOcclude() + "\t" + light + "\t" + s.isRandomlyTicking()
                    + "\t" + b.getStateDefinition().getProperties());
        }
        Files.write(Paths.get("allstates.tsv"), out);
    }
}
