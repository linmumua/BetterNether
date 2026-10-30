package dev.mumu.betternether.worldgen;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.List;
import java.util.Random;

/**
 * 配置特征：在给定位置上「放什么」。对应上游 {@code feature/*.json}。
 *
 * <h2>为什么每个特征自己决定「能不能放」</h2>
 * 原版特征的 {@code place()} 返回 boolean，放置失败只是「这一步没放成」，不是错误。
 * 这里的实现保持同样的语义：拿不到可写的目标就静默返回，绝不越界写、绝不吞掉异常。
 *
 * <h2>{@code wover:template} 的坐标约定（假设，写明依据）</h2>
 * 上游六个 placed_feature（{@code tree_crimson_glowing} / {@code tree_crimson_pine} /
 * {@code big_warped_tree} / {@code old_red_mushroom} / {@code old_brown_mushroom} /
 * {@code war_deadwood}）用 {@code wover:template} 引用结构模板。落地规则：
 * <ul>
 *   <li>原点 = 位置 + {@code (0, offset_y, 0)}（{@code offset_y} 来自模板条目）。</li>
 *   <li>模板内坐标以<b>角</b>为原点，即 {@code 原点 + (x, y, z)}，与
 *       {@code StructureTemplate} 的默认放置一致。不旋转、不镜像：上游 JSON 里没有任何
 *       旋转依据，凭空的随机旋转只会让同一棵树每次朝向不同。</li>
 * </ul>
 */
public sealed interface Feature {
    /** 在 {@code point} 上尝试放置。返回值只用于统计，调用方不依赖它。 */
    boolean place(WorldView world, Point point);

    /** 单个方块状态提供器（{@code to_place} / {@code provider} / {@code state}）。 */
    sealed interface BlockProvider {
        /** 采样一个方块状态；{@code null} 表示这一档在当前包里不可用。 */
        BlockData sample(Random random);

        /** 单一状态。 */
        record Single(BlockData data) implements BlockProvider {
            @Override
            public BlockData sample(Random random) {
                return data;
            }
        }

        /** 按权重取样。权重为 0 或负数的条目在装载期被剔除。 */
        record Weighted(List<BlockData> data, int[] weights, int total) implements BlockProvider {
            @Override
            public BlockData sample(Random random) {
                if (total <= 0) {
                    return null;
                }
                int roll = random.nextInt(total);
                for (int i = 0; i < data.size(); i++) {
                    roll -= weights[i];
                    if (roll < 0) {
                        return data.get(i);
                    }
                }
                return data.get(data.size() - 1);
            }
        }
    }

    /**
     * {@code minecraft:simple_block}：在位置上放一个方块。
     *
     * <p>判据是「这里可以覆盖」：空气或可替换（见 {@code BlockPredicates#replaceable}）。
     * 原版还会问 {@code canSurvive}，服务端 API 拿不到；放置前的
     * {@code matching_block_tag air} / 地面谓词已经把位置筛过一遍，这里只兜底。
     */
    record SimpleBlock(BlockProvider provider) implements Feature {
        @Override
        public boolean place(WorldView world, Point point) {
            BlockData data = provider.sample(world.random());
            if (data == null) {
                return false;
            }
            int x = point.x();
            int y = point.y();
            int z = point.z();
            if (!world.inRegion(x, y, z) || !BlockPredicates.replaceable(world, x, y, z)) {
                return false;
            }
            world.setBlock(x, y, z, data);
            return true;
        }
    }

    /**
     * {@code wover:random_patch}：**逐行照抄** WorldWeaver
     * {@code de.ambertation.wover.feature.impl.random.RandomPatchFeature#place}
     * （release/26.3 源码实读，非推测）。
     *
     * <p>原实现的关键三行：
     * <pre>
     * xzSpread = this.xzSpread + 1;  ySpread = this.ySpread + 1;
     * mutable.setWithOffset(origin,
     *     random.nextInt(xzSpread) - random.nextInt(xzSpread),
     *     random.nextInt(ySpread) - random.nextInt(ySpread),
     *     random.nextInt(xzSpread) - random.nextInt(xzSpread));
     * if (feature.value().place(level, generator, random, mutable)) placed++;
     * </pre>
     * 即：每个轴是**两次 {@code nextInt(spread+1)} 相减**，顺序 x→y→z；内层 placed feature
     * （含它自己的修饰符）在偏移后的位置执行；{@code tries} 全部跑完，返回 {@code placed > 0}。
     *
     * <p>我此前的 {@code nextInt(spread*2+1) - spread} 有三处偏差：只消耗 **1 次**随机数、
     * 分布形状不同（三角 vs 均匀差分）、且边界包含关系不同。这三条任何一条都会让同一 seed
     * 生成出另一个世界。
     *
     * <p>唯一的本地改动：每个候选先判 {@link WorldView#inRegion} —— Folia 下越界读会抛异常，
     * 上游单线程无此问题。这不改变随机数消耗，只丢弃越界候选。
     */
    record RandomPatch(int tries, int xzSpread, int ySpread, Feature inner) implements Feature {
        @Override
        public boolean place(WorldView world, Point point) {
            int xz = xzSpread + 1;
            int y = ySpread + 1;
            int placed = 0;
            for (int i = 0; i < tries; i++) {
                int dx = world.random().nextInt(xz) - world.random().nextInt(xz);
                int dy = world.random().nextInt(y) - world.random().nextInt(y);
                int dz = world.random().nextInt(xz) - world.random().nextInt(xz);
                Point candidate = point.offset(dx, dy, dz);
                if (!world.inRegion(candidate.x(), candidate.y(), candidate.z())) {
                    continue;
                }
                if (inner.place(world, candidate)) {
                    placed++;
                }
            }
            return placed > 0;
        }
    }

    /**
     * {@code wover:pillar}：照抄 wover {@code PillarFeature#place}（release/26.3 源码实读）。
     *
     * <pre>
     * int maxHeight = this.maxHeight.sample(rnd);     // 先 max
     * int minHeight = this.minHeight.sample(rnd);     // 再 min
     * posnow = origin.mutable(); posnow.move(direction);
     * for (height = 1; height &lt; maxHeight; ++height) {
     *     if (!allowedPlacement.test(level, posnow)) { maxHeight = height; break; }
     *     posnow.move(direction);
     * }
     * if (maxHeight &lt; minHeight) return false;
     * if (!transformer.canPlace.at(...)) return false;
     * posnow = origin.mutable();
     * for (height = 0; height &lt; maxHeight; ++height) {
     *     state = transform(height, maxHeight - 1, posnow, rnd, level);
     *     level.setBlock(posnow, state, SET_SILENT);
     *     posnow.move(direction);
     * }
     * </pre>
     *
     * <p>我此前把它简化成「高度 = max(min, sample(max))、遇阻即停」—— 丢了三点：
     * <b>max 与 min 都从 IntProvider 采样（顺序 max→min）</b>、<b>实际高度由
     * allowedPlacement 截断</b>（不是采样值）、以及<b>逐格按高度套 transform</b>。
     *
     * <p>{@code transform} 的取值与公式（同一份源码）：
     * <ul>
     *   <li>{@code size_decrease} → {@code size = max(0,min(7, tip - height))}</li>
     *   <li>{@code size_increase} → {@code size = max(0,min(7, height))}</li>
     *   <li>{@code bottom_grow} → {@code bottom = (height == tip)}</li>
     *   <li>{@code bottom} → {@code bottom = (height == 0)}</li>
     *   <li>{@code triple_shape_fill} → {@code bottom/middle/top}，且额外要求尖端上方
     *       <b>不可</b>放置（{@code !allowed.test(aboveTop)}），否则整根放弃</li>
     * </ul>
     * 其中 {@code tip = maxHeight - 1}，因为源码把 {@code maxHeight - 1} 传给 transform。
     *
     * <p>方块状态按「id + 属性」逐格重新解析（{@link dev.mumu.betternether.terrain.CraftEngineLookup}），
     * 因为 transform 改的就是属性；{@code fallback} 用于单测（无 CE 注册表时按固定状态放）。
     */
    record Pillar(String id, java.util.Map<String, String> props, BlockData fallback,
                  Point.Direction direction, IntProvider minHeight, IntProvider maxHeight,
                  BlockPredicates.Predicate allowedPlacement, String transform) implements Feature {
        @Override
        public boolean place(WorldView world, Point point) {
            int max = maxHeight.sample(world.random());
            int min = minHeight.sample(world.random());

            Point probe = point.offset(direction, 1);
            int height;
            for (height = 1; height < max; height++) {
                if (!allowedPlacement.test(world, probe)) {
                    max = height;
                    break;
                }
                probe = probe.offset(direction, 1);
            }
            if (max < min) {
                return false;
            }
            // triple_shape_fill 的 canPlace：尖端上方必须是「不可放置」的（有净空才立得住）
            if ("triple_shape_fill".equals(transform) && allowedPlacement.test(world, probe)) {
                return false;
            }

            int tip = max - 1;
            Point now = point;
            boolean placed = false;
            for (height = 0; height < max; height++) {
                BlockData data = stateFor(height, tip);
                if (data != null && world.inRegion(now.x(), now.y(), now.z())) {
                    world.setBlock(now.x(), now.y(), now.z(), data);
                    placed = true;
                }
                now = now.offset(direction, 1);
            }
            return placed;
        }

        /** 按 transform 改写属性后重新解析 CE 状态；{@code id == null}（单测）时用 {@code fallback}。 */
        private BlockData stateFor(int height, int tip) {
            if (id == null) {
                return fallback;
            }
            java.util.Map<String, String> merged = new java.util.LinkedHashMap<>(props);
            switch (transform) {
                case "size_decrease" -> merged.put("size",
                        String.valueOf(Math.max(0, Math.min(7, tip - height))));
                case "size_increase" -> merged.put("size",
                        String.valueOf(Math.max(0, Math.min(7, height))));
                case "bottom_grow" -> merged.put("bottom", String.valueOf(height == tip));
                case "bottom" -> merged.put("bottom", String.valueOf(height == 0));
                case "triple_shape_fill" -> merged.put("triple_shape",
                        height == 0 ? "bottom" : (height == tip ? "top" : "middle"));
                default -> {
                    // none：原样
                }
            }
            BlockData resolved = dev.mumu.betternether.terrain.CraftEngineLookup.lookup(id, merged);
            return resolved != null ? resolved : fallback;
        }
    }

    /**
     * {@code minecraft:block_column}：按层放一列，每层有自己的高度与方块。
     *
     * <p>语义：逐层采样高度，沿 {@code direction} 连续放置，遇到不可放的位置就停。
     * {@code prioritize_tip} 为真时，空间不够也要优先保住**最后一层**（尖端）—— 这是
     * 原版的意图：芦苇少了顶段就不是芦苇了。实现方式是先算总高，空间不足时从前面几层里
     * 扣掉差额，而不是直接截断尾部。
     */
    record BlockColumn(Point.Direction direction, List<Layer> layers, boolean prioritizeTip,
                       BlockPredicates.Predicate allowedPlacement) implements Feature {

        /** 一层：高度区间 + 方块。 */
        public record Layer(IntProvider height, BlockProvider provider) {
        }

        @Override
        public boolean place(WorldView world, Point point) {
            int[] heights = new int[layers.size()];
            int total = 0;
            for (int i = 0; i < layers.size(); i++) {
                heights[i] = Math.max(0, layers.get(i).height().sample(world.random()));
                total += heights[i];
            }
            if (total == 0) {
                return false;
            }
            // 先量出实际能放多少格。
            int room = 0;
            while (room < total) {
                Point probe = point.offset(direction, room);
                if (!world.inRegion(probe.x(), probe.y(), probe.z())
                        || !allowedPlacement.test(world, probe)) {
                    break;
                }
                room++;
            }
            if (room == 0) {
                return false;
            }
            if (room < total) {
                // 逐行照抄 vanilla BlockColumnFeature#truncate（服务端 jar 反汇编）：
                //   excess = total - placed ; step = prioritizeTip ? +1 : -1
                //   起点 = prioritizeTip ? 0 : length-1 ; 终点 = prioritizeTip ? length : -1
                //   逐层扣 min(height, excess)，直到 excess 用完
                // 即：保尖端时**从第一层开始扣**，不保尖端时从最后一层开始扣。
                int excess = total - room;
                int step = prioritizeTip ? 1 : -1;
                int index = prioritizeTip ? 0 : heights.length - 1;
                int stop = prioritizeTip ? heights.length : -1;
                for (; index != stop && excess > 0; index += step) {
                    int cut = Math.min(heights[index], excess);
                    heights[index] -= cut;
                    excess -= cut;
                }
            }
            int cursor = 0;
            boolean placed = false;
            for (int i = 0; i < layers.size() && cursor < room; i++) {
                BlockProvider provider = layers.get(i).provider();
                for (int n = 0; n < heights[i] && cursor < room; n++) {
                    BlockData data = provider.sample(world.random());
                    if (data == null) {
                        continue;
                    }
                    Point target = point.offset(direction, cursor);
                    if (!world.inRegion(target.x(), target.y(), target.z())) {
                        return placed;
                    }
                    world.setBlock(target.x(), target.y(), target.z(), data);
                    placed = true;
                    cursor++;
                }
            }
            return placed;
        }
    }

    /**
     * {@code wover:place_block} → wover {@code PlaceFacingBlockFeature#placeBlock}（源码实读）。
     *
     * <pre>
     * BlockState targetState = provider.getState(...);          // 每次放置**采样一次**
     * for (Direction dir : directions) {
     *     testPos = pos.relative(dir);
     *     lookupState = targetState.setValue(FACING, dir);
     *     if (level.getBlockState(testPos).isAir() &amp;&amp; lookupState.canSurvive(level, testPos)) {
     *         level.setBlock(testPos, lookupState, SET_SILENT);
     *         return true;
     *     }
     * }
     * </pre>
     *
     * <p>三处与我上一版不同，都按源码改了：
     * <ol>
     *   <li>方块放在 <b>{@code pos.relative(dir)}</b>（相邻格），不是入参位置本身；</li>
     *   <li>目标格必须是 <b>空气</b>（不是「可替换」），我上一版允许液体/草；</li>
     *   <li>要为方块设 <b>{@code FACING = dir}</b>，且要求 {@code canSurvive}。</li>
     * </ol>
     * 缺口：{@code canSurvive} 服务端 API 拿不到（CE 的 {@code BlockStateWrapper.canSurvive}
     * 需要 CE 自己的 WorldAccessor），这里以「目标格是空气」代替并在此注明；FACING 仅在方块
     * 本身是 {@code Directional} 时设置（本包多数 betternether 方块没有该属性）。
     */
    record PlaceBlock(List<Point.Direction> dirs, BlockProvider provider) implements Feature {
        @Override
        public boolean place(WorldView world, Point point) {
            BlockData sampled = provider.sample(world.random());
            if (sampled == null) {
                return false;
            }
            for (Point.Direction dir : dirs) {
                Point target = point.offset(dir, 1);
                if (!world.inRegion(target.x(), target.y(), target.z())) {
                    continue;
                }
                if (!world.isAir(target.x(), target.y(), target.z())) {
                    continue;
                }
                BlockData state = facing(sampled, dir);
                // 上游判的是 `isAir && lookupState.canSurvive(level, testPos)` ——
                // 「贴墙」这件事由 canSurvive 表达，不是「旁边有没有实心方块」。
                // 壁挂类方块（光照蘑菇、疣芽）的 canSurvive 要求有支撑面，因此四周空气时
                // 自然返回 false。问不出来时 BlockPredicates 退回「恒真」并记账，不静默。
                if (!BlockPredicates.wouldSurvive(world, state, target.x(), target.y(), target.z())) {
                    continue;
                }
                world.setBlock(target.x(), target.y(), target.z(), state);
                return true;
            }
            return false;
        }

        /** 方块有 {@code FACING} 就按方向设；没有属性时原样返回（上游会因此放置失败，这里放行）。 */
        private static BlockData facing(BlockData data, Point.Direction dir) {
            if (!(data instanceof org.bukkit.block.data.Directional directional)) {
                return data;
            }
            BlockData copy = data.clone();
            try {
                ((org.bukkit.block.data.Directional) copy).setFacing(
                        org.bukkit.block.BlockFace.valueOf(dir.name()));
            } catch (IllegalArgumentException | UnsupportedOperationException ignored) {
                return data;
            }
            return copy;
        }
    }

    /**
     * {@code minecraft:overlay}：子特征都在同一个位置上各放一次。
     *
     * <p>原版 {@code OverlayFeature} 的语义就是「依次应用所有子特征」，没有任何权重。
     */
    record Overlay(List<Feature> features) implements Feature {
        @Override
        public boolean place(WorldView world, Point point) {
            boolean placed = false;
            for (Feature feature : features) {
                placed |= feature.place(world, point);
            }
            return placed;
        }
    }

    /**
     * {@code minecraft:random_selector}：按 {@code chance} 权重在子 placed feature 里选一个，
     * 都不命中就用 {@code default}。
     *
     * <p>注意子项是 <b>placed feature</b>：选中之后要先跑它自己的放置修饰符，再放它的特征。
     */
    record RandomSelector(List<Selection> selections, Feature fallback) implements Feature {

        /** 一个候选：权重 + 已包装的内层 placed feature。 */
        public record Selection(float chance, Feature feature) {
        }

        @Override
        public boolean place(WorldView world, Point point) {
            float roll = world.random().nextFloat();
            for (Selection selection : selections) {
                roll -= selection.chance();
                if (roll < 0.0F) {
                    return selection.feature().place(world, point);
                }
            }
            return fallback != null && fallback.place(world, point);
        }
    }

    /**
     * 一个 placed feature 当作「特征」参与外层放置：先跑它自己的修饰符，再放它的特征。
     *
     * <p>上游的 {@code overlay} / {@code random_patch} / {@code random_selector} 的子项都是
     * placed feature（而不是配置特征），所以需要这一层包装把「内层修饰符」带进去。
     *
     * <p>内层链同样必须走 {@link Pipeline#placeChain} 的逐位置 DFS。批量写法
     * （先 {@code Pipeline.run} 收齐内层位置、再 {@code Feature#place} 逐个放）在本层是
     * <b>更隐蔽</b>的错误来源：外层已经消耗过随机数，内层再批量变形就会把「内层抽样」与
     * 「内层放置」拆成两段，与上游 {@code PlacedFeature#place} 的栈式执行顺序不一致。
     */
    record Wrapped(List<PlacementModifier> placement, Feature feature) implements Feature {
        @Override
        public boolean place(WorldView world, Point point) {
            // 上游 PlacedFeature#place 的返回值是「放置计数 > 0」，不是逐个子特征的 OR。
            return Pipeline.placeChain(placement, point, world, feature) > 0;
        }
    }

    /**
     * {@code wover:template}：**按 WorldWeaver release/26.3 源码实读**实现。
     *
     * <p>源码依据（3 个文件）：
     * <ul>
     *   <li>{@code wover-feature-api/.../feature/impl/features/FeatureTemplateImpl.java}：
     *       {@code generate()} → {@code generateCentered(level, pos.above(offsetY), rotation, mirror)}，
     *       放置前先 {@code canGenerate}（bedrock 守卫）；</li>
     *   <li>{@code wover-feature-api/.../feature/impl/features/FeatureTemplateImpl#generateIfPlaceable}：
     *       先取随机旋转、再取随机镜像（各消耗 1 个随机数，顺序 rotation → mirror）；</li>
     *   <li>{@code wover-structure-api/.../api/StructureNBT.java#getCenteredPos}：
     *       尺寸向量先按镜像取负、再按旋转交换，最后
     *       {@code pos.offset(-x >> 1, 0, -z >> 1)}。</li>
     * </ul>
     *
     * <p><b>因此「角原点」是错的</b>：模板以 x/z **居中**落在位置上（与我此前根据树干中位
     * ≈ (size-1)/2 推出的结论一致，现在有源码为证）。
     *
     * <p>旋转/镜像数学用原版 {@code StructureTemplate#transform}：
     * 坐标先镜像（LEFT_RIGHT → z 取负，FRONT_BACK → x 取负）再旋转
     * （CW90 → (-z, x)，180 → (-x, -z)，CCW90 → (z, -x)）。方块**状态**的旋转/镜像未做：
     * 本包里的 betternether 方块没有 facing/axis 属性（CE 侧 auto_state 折叠），
     * 原版方块里只有少数有朝向，留作已知缺口。
     *
     * <p>覆盖语义与原版 {@code placeInWorld} 一致：**直接覆盖**（不再只覆盖 air/replaceable）。
     * 越出 {@code LimitedRegion} 的方块裁掉。模板里的空气在装载期已剔除。
     */
    record Template(List<TemplateChoice> choices, float totalWeight) implements Feature {

        /** 一个模板选项：结构 + {@code offset_y} + 权重。 */
        public record TemplateChoice(TemplateData template, int offsetY, float weight) {
        }

        @Override
        public boolean place(WorldView world, Point point) {
            TemplateChoice choice = pick(world.random());
            if (choice == null) {
                return false;
            }
            TemplateData template = choice.template();

            // 1) 随机旋转 → 随机镜像（顺序与 wover 的 generateIfPlaceable 一致，各消耗 1 个随机数）
            int rotation = world.random().nextInt(4);
            int mirror = world.random().nextInt(3);

            // 2) bedrock 守卫：wover 从 pos 起每 2 格检查一次，命中基岩就整棵放弃
            for (int i = 0; i < template.sizeY(); i += 2) {
                BlockData here = world.blockAt(point.x(), point.y() + i, point.z());
                if (here != null && here.getMaterial() == org.bukkit.Material.BEDROCK) {
                    return false;
                }
            }

            // 3) 居中：尺寸向量先镜像取负、再旋转交换，然后 (-extent) >> 1
            int sizeX = template.sizeX();
            int sizeZ = template.sizeZ();
            if (mirror == 2) {            // FRONT_BACK
                sizeX = -sizeX;
            }
            if (mirror == 1) {            // LEFT_RIGHT
                sizeZ = -sizeZ;
            }
            int rotatedX = switch (rotation) {
                case 1 -> -sizeZ;         // CLOCKWISE_90
                case 2 -> -sizeX;         // CLOCKWISE_180
                case 3 -> sizeZ;          // COUNTERCLOCKWISE_90
                default -> sizeX;
            };
            int rotatedZ = switch (rotation) {
                case 1 -> sizeX;
                case 2 -> -sizeZ;
                case 3 -> -sizeX;
                default -> sizeZ;
            };
            int originX = point.x() + ((-rotatedX) >> 1);
            int originY = point.y() + choice.offsetY();
            int originZ = point.z() + ((-rotatedZ) >> 1);

            // 4) 逐格放置（局部坐标先镜像再旋转）
            boolean placed = false;
            for (int i = 0; i < template.size(); i++) {
                int localX = template.x(i);
                int localZ = template.z(i);
                if (mirror == 1) {
                    localZ = -localZ;
                } else if (mirror == 2) {
                    localX = -localX;
                }
                int px;
                int pz;
                switch (rotation) {
                    case 1 -> {                // CLOCKWISE_90: (-z, x)
                        px = -localZ;
                        pz = localX;
                    }
                    case 2 -> {                // CLOCKWISE_180
                        px = -localX;
                        pz = -localZ;
                    }
                    case 3 -> {                // COUNTERCLOCKWISE_90: (z, -x)
                        px = localZ;
                        pz = -localX;
                    }
                    default -> {
                        px = localX;
                        pz = localZ;
                    }
                }
                int x = originX + px;
                int y = originY + template.y(i);
                int z = originZ + pz;
                BlockData data = template.state(i);
                if (data == null || !world.inRegion(x, y, z)) {
                    continue;
                }
                world.setBlock(x, y, z, orient(data, mirror, rotation));
                placed = true;
            }
            return placed;
        }

        /**
         * 方块**状态**的镜像 + 旋转。
         *
         * <p>顺序与原版 {@code StructureTemplate} 一致：**先镜像再旋转**。
         * API 已用本地 {@code javap} 核实（不是猜）：
         * {@code org.bukkit.block.data.BlockData#mirror(org.bukkit.block.structure.Mirror)} 与
         * {@code #rotate(org.bukkit.block.structure.StructureRotation)}，两者都是**原地修改**
         * 且返回 void，所以必须先 {@code clone()}，否则会把模板缓存里的状态改掉、污染后续所有放置。
         *
         * <p>两个枚举的声明顺序与 wover 取样方式完全对应：
         * {@code StructureRotation.values()} = {NONE, CLOCKWISE_90, CLOCKWISE_180, COUNTERCLOCKWISE_90}
         * 对应 {@code Rotation.getRandom} 的 {@code nextInt(4)}；
         * {@code Mirror.values()} = {NONE, LEFT_RIGHT, FRONT_BACK} 对应
         * {@code Mirror.values()[random.nextInt(3)]}（见 {@code StructureNBT#getRandomMirror}）。
         */
        private static BlockData orient(BlockData data, int mirror, int rotation) {
            BlockData copy = data.clone();
            if (mirror != 0) {
                copy.mirror(org.bukkit.block.structure.Mirror.values()[mirror]);
            }
            if (rotation != 0) {
                copy.rotate(org.bukkit.block.structure.StructureRotation.values()[rotation]);
            }
            return copy;
        }

        private TemplateChoice pick(Random random) {
            if (choices.isEmpty() || totalWeight <= 0.0F) {
                return null;
            }
            // wover 的 RandomizedWeightedList#getRandomValue 用 **nextDouble()** * totalWeight，
            // 再与累计权重比较（randomValue < cumulative）。用 nextFloat 会消耗不同的随机位。
            double roll = random.nextDouble() * totalWeight;
            double cumulative = 0.0;
            for (TemplateChoice choice : choices) {
                cumulative += choice.weight();
                if (roll < cumulative) {
                    return choice;
                }
            }
            return choices.get(choices.size() - 1);
        }
    }

    /**
     * 纯形状特征：逻辑写在别处（Java 移植的上游 feature），这里只做适配。
     *
     * <p>上游有 8 个 feature 是**算法而不是数据**：{@code wart_cap} 的 3D 椭球 + 挖壳、
     * 三个 bush 的逐格 {@code nextFloat()} 判定、{@code lucis}/{@code soul_lily}/
     * {@code jellyfish_mushroom} 的分支生长。既有类型无法等价表达 —— 强行拼会把随机数的
     * 个数与顺序改掉，而这两者必须与上游一致。
     */
    record Custom(String name, Placer placer) implements Feature {
        /** 形状实现。与 {@link Feature#place} 同签名，便于直接搬运上游的 placement 代码。 */
        public interface Placer {
            boolean place(WorldView world, Point point);
        }

        @Override
        public boolean place(WorldView world, Point point) {
            return placer.place(world, point);
        }
    }

    /**
     * {@code betternether:twisting_vines}：逐行移植
     * {@code upstream/.../world/features/TwistedVinesFeature}。
     *
     * <pre>
     * scale = MAX_HEIGHT / 128f; RANDOM_BOUND = (int)(6 * scale)
     * if (!canPlaceAt(pos)) return false;          // 下方必须是 warped_nylium 或 twisting_vines
     * y = pos.y + random.nextInt(RANDOM_BOUND)
     * for (j = 0; j &lt; RANDOM_BOUND; j++) {
     *     p = (x, y - j, z)
     *     if (isAir(p) &amp;&amp; canPlaceAt(p)) {
     *         h = random.nextInt(20) + 1
     *         for (n = 0; n &lt; h; n++) {
     *             if (!isAir(p.above())) { set(p, TWISTING_VINES); break; }
     *             set(p, TWISTING_VINES_PLANT)
     *         }
     *         set(p, TWISTING_VINES)               // 循环后再写一次（源码就是这样）
     *         break
     *     }
     * }
     * </pre>
     *
     * <p>它写的是**原版**方块（`twisting_vines` / `twisting_vines_plant`），所以材质比较是精确的。
     * {@code MAX_HEIGHT} 用 {@code maxY-minY+1}（本生成器 = 128，与上游 {@code getGenDepth()} 一致），
     * 因此 {@code scale = 1}、{@code RANDOM_BOUND = 6}。
     */
    record TwistedVines(BlockData plant, BlockData tip) implements Feature {
        @Override
        public boolean place(WorldView world, Point point) {
            int maxHeight = world.maxY() - world.minY() + 1;
            float scale = maxHeight / 128.0F;
            int randomBound = (int) (6 * scale);
            if (randomBound <= 0 || !canPlaceAt(world, point)) {
                return false;
            }
            int y = point.y() + world.random().nextInt(randomBound);
            for (int j = 0; j < randomBound; j++) {
                int currentY = y - j;
                Point now = new Point(point.x(), currentY, point.z());
                if (!world.isAir(now.x(), now.y(), now.z()) || !canPlaceAt(world, now)) {
                    continue;
                }
                int height = world.random().nextInt(20) + 1;
                Point cursor = now;
                for (int n = 0; n < height; n++) {
                    Point above = cursor.offset(Point.Direction.UP, 1);
                    if (!world.isAir(above.x(), above.y(), above.z())) {
                        set(world, cursor, tip);
                        break;
                    }
                    set(world, cursor, plant);
                    cursor = above;
                }
                set(world, cursor, tip);
                return true;
            }
            return true;
        }

        private static void set(WorldView world, Point point, BlockData data) {
            if (data != null && world.inRegion(point.x(), point.y(), point.z())) {
                world.setBlock(point.x(), point.y(), point.z(), data);
            }
        }

        /**
         * 源码 {@code canPlaceAt}：{@code below == Blocks.WARPED_NYLIUM || below == Blocks.TWISTING_VINES}。
         *
         * <p>注意**不是** {@code twisting_vines_plant} —— 上游比的是那两个常量本身，所以长出来的
         * 藤蔓继续往上爬时只认「尖端」这一种（`plant` 不认），而地面上只认诡异菌岩。
         * 身份比较优先走 {@link WorldView#blockId}（CE/原版注册名，能区分同材质不同方块），
         * 拿不到才退回材质比较。
         */
        private boolean canPlaceAt(WorldView world, Point point) {
            Point below = point.offset(Point.Direction.DOWN, 1);
            if (!world.inRegion(below.x(), below.y(), below.z())) {
                return false;
            }
            String id = world.blockId(below.x(), below.y(), below.z());
            if (id != null) {
                return "minecraft:warped_nylium".equals(id) || "minecraft:twisting_vines".equals(id);
            }
            BlockData data = world.blockAt(below.x(), below.y(), below.z());
            if (data == null) {
                return false;
            }
            Material material = data.getMaterial();
            return material == Material.WARPED_NYLIUM || material == Material.TWISTING_VINES;
        }

        private static boolean matches(BlockData here, BlockData expected) {
            return expected != null && here.getMaterial() == expected.getMaterial();
        }
    }

    /**
     * {@code minecraft:spring_feature}：按主代理对 {@code SpringFeature#place} 的
     * {@code javap -c -p} 结论实现（我先前那版是**部分方法**的误读，已作废）。
     *
     * <p>判定顺序与计数（字节码行号见注释，均为 26.3 服务端 jar）：
     * <ol>
     *   <li>{@code pos.above()} 必须属于 {@code valid_blocks}；</li>
     *   <li>{@code requires_block_below} 为真时 {@code pos.below()} 也必须属于；
     *       随后 <b>pos 允许是空气、也允许本身就是 valid_blocks</b>（63..84 行的两个分支），
     *       不是「必须空气」；</li>
     *   <li>对 west/east/north/south/<b>below</b> 五向分别统计：
     *       {@code rockCount} = 属于 {@code valid_blocks} 的方向数，
     *       {@code holeCount} = {@code isEmptyBlock} 的方向数；</li>
     *   <li>必须 {@code rockCount == 配置值}（缺省 4）且 {@code holeCount == 配置值}（缺省 1）
     *       才放行（299..316），**不是**「四邻 valid ≥ 4 就拒绝」；</li>
     *   <li>写入 {@code state} 之后调用 {@code level.scheduleTick(pos, fluid, 0)}
     *       （334..350）—— 漏掉这一步，泉眼不会立刻流动。</li>
     * </ol>
     */
    record Spring(BlockData state, boolean requiresBlockBelow, int rockCount, int holeCount,
                  List<BlockData> validBlocks) implements Feature {
        @Override
        public boolean place(WorldView world, Point point) {
            if (state == null || validBlocks.isEmpty()) {
                return false;
            }
            if (!inValid(world, point.offset(Point.Direction.UP, 1))) {
                return false;
            }
            if (requiresBlockBelow && !inValid(world, point.offset(Point.Direction.DOWN, 1))) {
                return false;
            }
            // pos 允许空气，也允许本身就是 valid_blocks（63..84）
            if (!world.isAir(point.x(), point.y(), point.z()) && !inValid(world, point)) {
                return false;
            }
            int rocks = 0;
            int holes = 0;
            for (Point.Direction dir : new Point.Direction[]{Point.Direction.WEST,
                    Point.Direction.EAST, Point.Direction.NORTH, Point.Direction.SOUTH,
                    Point.Direction.DOWN}) {
                Point neighbour = point.offset(dir, 1);
                if (inValid(world, neighbour)) {
                    rocks++;
                }
                if (world.inRegion(neighbour.x(), neighbour.y(), neighbour.z())
                        && world.isAir(neighbour.x(), neighbour.y(), neighbour.z())) {
                    holes++;
                }
            }
            if (rocks != rockCount || holes != holeCount) {
                return false;
            }
            if (!world.inRegion(point.x(), point.y(), point.z())) {
                return false;
            }
            world.setBlock(point.x(), point.y(), point.z(), state);
            // 334..350：放完必须让流体立刻 tick 一次（上游 level.scheduleTick(pos, fluid, 0)）
            world.scheduleFluidTick(point.x(), point.y(), point.z());
            return true;
        }

        private boolean inValid(WorldView world, Point point) {
            if (!world.inRegion(point.x(), point.y(), point.z())) {
                return false;
            }
            BlockData here = world.blockAt(point.x(), point.y(), point.z());
            if (here == null) {
                return false;
            }
            for (BlockData candidate : validBlocks) {
                if (here.getMaterial() == candidate.getMaterial()) {
                    return true;
                }
            }
            return false;
        }
    }
}
