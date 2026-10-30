package dev.mumu.betternether.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 放置修饰符：把「一个位置」变成「一组位置」。对应上游 {@code placed_feature} 的
 * {@code placement} 数组，语义按原版 {@code PlacementModifier}（一步读入参、写写出参）。
 *
 * <h2>wover 修饰符的假设（源码不在本仓库，逐条写明）</h2>
 * <ul>
 *   <li>{@code wover:every_layer} —— 按 {@code min}/{@code top} 在整列上展开。{@code top}
 *       缺省为 true（地面层：空气且下方实心）；{@code top:false} 为天花板层（空气且
 *       <b>上方</b>实心）。依据是上游用法：{@code patch_black_vine} / {@code patch_neon_equisetum}
 *       用 {@code top:false}，而它们的特征是「从天花板垂下」，内层谓词还要求位置上方是地形。</li>
 *   <li>{@code wover:solid_in_dir} —— 沿方向最多走 {@code dist} 格，撞到实心方块后回退
 *       {@code offset_in_dir} 格（缺省 1）。依据：{@code jellyfish_mushroom} 只写
 *       {@code dist:4} 而后续要求「这里空气、下方是菌岩」，只有「停下后回退一格」才能得到
 *       「地面正上方那格」。{@code dir} 缺省为 {@code ["down"]}（同一依据）。
 *       {@code random_select:true} 时在多个方向的结果里随机取一个，否则按 {@code dir} 顺序取第一个。</li>
 *   <li>{@code wover:offset} —— {@code blocks:[dx,dy,dz]}，固定偏移。</li>
 *   <li>{@code wover:all} —— 空对象，按「原样透传」处理。上游只在被跳过的特征里出现，
 *       这里保留实现是为了将来若它带上字段时能明确失败（见装载器的参数校验）。</li>
 * </ul>
 */
public sealed interface PlacementModifier {
    /**
     * @param in  上一步的位置（只读）
     * @param out 本步的结果（只写）
     */
    void apply(List<Point> in, List<Point> out, WorldView world);
    /**
     * 把一个位置交给本修饰符，**逐位置就地**把结果交给下游（{@code next}）。
     *
     * <p>这是生产的执行入口（{@link Pipeline#placeChain}）：上游 wover 的 placed modifier 走
     * {@code FeaturePlacer} 的 stack DFS，每个位置<b>同步</b>跑完整条链再回到本修饰符的下一步。
     * 缺省实现 = 「先 {@link #apply} 单点展开、再按序输出」，只有当修饰符**没有**跨位置的扫描
     * 副作用与随机消耗时才与上游等价。
     *
     * <p>{@link CountOnEveryLayer} 与 {@link EveryLayer} 都带扫描 + 多次随机，必须自己 override
     * 成惰性版本（否则随机流顺序整体错位：先抽完全部位置再放置 ≠ 逐位置放置）。
     */
    default void visit(Point point, WorldView world, Consumer<Point> next) {
        List<Point> expanded = new java.util.ArrayList<>(1);
        apply(List.of(point), expanded, world);
        for (int i = 0; i < expanded.size(); i++) {
            next.accept(expanded.get(i));
        }
    }

    /** 高度分布的三种形态（上游只用到这三种）。 */
    enum HeightKind {
        UNIFORM,
        TRAPEZOID,
        CONSTANT
    }

    /**
     * 一个「锚点 + 偏移」的高度端点，例如 {@code above_bottom: 4} / {@code below_top: 4}。
     *
     * <p>**必须保留锚点而不能在装载期换算成绝对 y**：世界高度来自
     * {@link WorldView#minY()}/{@link WorldView#maxY()}，换一个世界高度就不一样了；
     * 折算后的绝对 y 会在那种情况下静默偏移。
     */
    record Anchored(String anchor, int offset) {
        int resolve(WorldView world) {
            return IntProvider.anchor(anchor, offset, world.minY(), world.maxY());
        }
    }

    /** {@code height_range} 的 {@code height}：锚点区间 + 分布形态。 */
    record HeightRangeSpec(Anchored min, Anchored max, HeightKind kind, int plateau) {
        HeightRangeSpec(Anchored min, Anchored max, HeightKind kind) {
            this(min, max, kind, 0);
        }

        int sample(WorldView world) {
            int low = min.resolve(world);
            int high = max.resolve(world);
            if (high < low) {
                int swap = low;
                low = high;
                high = swap;
            }
            return switch (kind) {
                case CONSTANT -> low;
                case UNIFORM -> new IntProvider.Uniform(low, high).sample(world.random());
                case TRAPEZOID -> new IntProvider.Trapezoid(low, high, plateau).sample(world.random());
            };
        }
    }

    /** xz 平面的四邻（wover 的 {@code IsNextTo} 只用这四个方向）。 */
    Point.Direction[] HORIZONTAL = {
            Point.Direction.WEST, Point.Direction.EAST,
            Point.Direction.NORTH, Point.Direction.SOUTH
    };

    /**
     * {@code minecraft:height_range}：把每个位置的 y 换成区间内的随机高度。
     *
     * <p>锚点在放置时按世界高度换算，见 {@link Anchored}。
     */
    record HeightRange(HeightRangeSpec height) implements PlacementModifier {
        @Override
        public void apply(List<Point> in, List<Point> out, WorldView world) {
            for (Point point : in) {
                int y = Constants.clamp(height.sample(world), world.minY(), world.maxY());
                out.add(new Point(point.x(), y, point.z()));
            }
        }
    }

    /**
     * {@code minecraft:count}：每个位置重复 {@code count} 次。
     *
     * <p>原版语义就是「复制」，不是「随机取几个」—— 这一点很容易写错，写错的表现是植被
     * 数量随机偏少。
     */
    record Count(IntProvider count) implements PlacementModifier {
        @Override
        public void apply(List<Point> in, List<Point> out, WorldView world) {
            for (Point point : in) {
                int times = count.sample(world.random());
                for (int i = 0; i < times; i++) {
                    out.add(point);
                }
            }
        }
    }

    /** {@code minecraft:in_square}：在区块内随机平移 {@code [0,15]}。 */
    /**
     * {@code minecraft:heightmap}：把每个位置的 y 换成该列高度图的值。
     *
     * <p>原版 {@code HeightmapPlacement#getPositions} 就是
     * {@code pos.setY(ctx.getHeight(types, x, z))}。这里只支持 {@code MOTION_BLOCKING}
     * （下界放置用到的唯一一种）；其它类型由装载器拒绝，不拿 MOTION_BLOCKING 顶替。
     *
     * <p>高度值来自 {@link WorldView#motionBlockingHeight}（生产实现读 NMS
     * {@code ServerLevel#getHeight}），**不是** {@code maxY()} 近似。
     */
    record Heightmap() implements PlacementModifier {
        @Override
        public void apply(List<Point> in, List<Point> out, WorldView world) {
            for (Point point : in) {
                if (!world.inRegion(point.x(), world.minY(), point.z())) {
                    continue;
                }
                out.add(new Point(point.x(),
                        world.motionBlockingHeight(point.x(), point.z()), point.z()));
            }
        }
    }

    record InSquare() implements PlacementModifier {
        @Override
        public void apply(List<Point> in, List<Point> out, WorldView world) {
            for (Point point : in) {
                out.add(new Point(point.x() + world.random().nextInt(16),
                        point.y(),
                        point.z() + world.random().nextInt(16)));
            }
        }
    }

    /**
     * {@code minecraft:biome}：只保留「这个坐标所属群系 == 正在装饰的群系」的位置。
     *
     * <p>这里刻意用 {@link WorldView#biomeAt} 而不是 2D 的列群系：竖带
     * （{@code upside_down_forest} 只在天花板带里）正是靠「在哪个高度读」体现的，
     * 否则挂在天花板上的植被会被判成地面的群系。
     */
    record Biome() implements PlacementModifier {
        @Override
        public void apply(List<Point> in, List<Point> out, WorldView world) {
            String decorated = world.decoratedBiome();
            if (decorated == null) {
                out.addAll(in);
                return;
            }
            for (Point point : in) {
                if (decorated.equals(world.biomeAt(point.x(), point.y(), point.z()))) {
                    out.add(point);
                }
            }
        }
    }

    /**
     * {@code minecraft:rarity_filter}：以 {@code 1/chance} 的概率保留。
     *
     * <p>26.3 字节码是 {@code random.nextFloat() < 1.0F / chance}（算子是 {@code <}），
     * <b>不是</b> {@code nextInt(chance) == 0}：两者对 {@code chance} 的取模分布、以及
     * {@code chance = 1} 时的随机消耗都不同 —— 原版在 {@code chance = 1} 时**仍然抽一次**
     * {@code nextFloat()}（只是必然通过），所以这一条也是随机流的一部分，不能优化掉。
     */
    record RarityFilter(int chance) implements PlacementModifier {
        @Override
        public void apply(List<Point> in, List<Point> out, WorldView world) {
            float threshold = 1.0F / Math.max(1, chance);
            for (Point point : in) {
                // chance <= 1 时 threshold = 1.0F，仍抽一次；nextFloat() ∈ [0,1) 恒 < 1.0F。
                if (world.random().nextFloat() < threshold) {
                    out.add(point);
                }
            }
        }
    }

    /**
     * {@code minecraft:count_on_every_layer}：**迭代加深**，逐行照抄 vanilla
     * {@code CountOnEveryLayerPlacement#modify}（服务端 jar 反汇编实录）。
     *
     * <pre>
     * for (BlockPos pos : input) {                 // **逐个输入位置**，不重排、不合并
     *     int offset = 0;
     *     boolean anyPlaced;
     *     do {
     *         anyPlaced = false;
     *         for (int i = 0; i &lt; count.sample(random); i++) {   // 条件处每轮采样一次
     *             int x = pos.getX() + random.nextInt(16);
     *             int z = pos.getZ() + random.nextInt(16);
     *             int top = ctx.getHeight(MOTION_BLOCKING, x, z);
     *             int y = findOnGroundYPosition(ctx, x, top, z, offset);
     *             if (y != Integer.MAX_VALUE) { emit(x, y, z); anyPlaced = true; }
     *         }
     *         offset++;
     *     } while (anyPlaced);
     * }
     * </pre>
     *
     * <p>三个容易写错的点：①{@code count.sample} 在 for 条件里，每判一次抽一次（含最后那次
     * 为 false 的判定）；②随机偏移是 {@code pos.getX() + nextInt(16)}，<b>基准是输入位置本身</b>，
     * 不是「取第一个输入点的区块原点」——跨链传入多个位置时会逐点各抽一次；
     * ③层扫描自顶向下、从该格真实 {@code MOTION_BLOCKING} 开始。
     */
    record CountOnEveryLayer(IntProvider count) implements PlacementModifier {
        @Override
        public void apply(List<Point> in, List<Point> out, WorldView world) {
            for (Point origin : in) {
                int offset = 0;
                boolean anyPlaced;
                do {
                    anyPlaced = false;
                    // `count.sample(random)` 在 for 条件里：每判一次抽一次（含最后那次 false）。
                    for (int i = 0; i < count.sample(world.random()); i++) {
                        int x = origin.x() + world.random().nextInt(16);
                        int z = origin.z() + world.random().nextInt(16);
                        int y = Layers.groundLayer(world, x, z,
                                world.motionBlockingHeight(x, z), offset);
                        if (y != Integer.MIN_VALUE) {
                            out.add(new Point(x, y, z));
                            anyPlaced = true;
                        }
                    }
                    offset++;
                } while (anyPlaced);
            }
        }

        /**
         * 惰性版本：每采到一个位置就**立刻**跑下游，再回到下一轮/下一次尝试。
         *
         * <p>上游 {@code CountOnEveryLayerPlacement} 在 {@code FeaturePlacer} 的 DFS 里就是这样：
         * 「采样 → 定位 → 交给 consumer → 继续」。先把整轮位置收完再统一放置会改变随机流
         * 顺序（下游的 {@code in_square} 抽样会全部挤到后面），位置总量相同但落点不同。
         */
        @Override
        public void visit(Point point, WorldView world, Consumer<Point> next) {
            int offset = 0;
            boolean anyPlaced;
            do {
                anyPlaced = false;
                for (int i = 0; i < count.sample(world.random()); i++) {
                    // 基准就是这一个输入位置本身（原版 pos.getX() + nextInt(16)），
                    // 不是区块原点，也不是链上第一个位置的区块原点。
                    int x = point.x() + world.random().nextInt(16);
                    int z = point.z() + world.random().nextInt(16);
                    int y = Layers.groundLayer(world, x, z, world.motionBlockingHeight(x, z), offset);
                    if (y != Integer.MIN_VALUE) {
                        anyPlaced = true;
                        next.accept(new Point(x, y, z));
                    }
                }
                offset++;
            } while (anyPlaced);
        }
    }

    /**
     * {@code wover:every_layer}：把每个位置展开成这一列上的<b>所有</b>层。
     *
     * <p>{@code top} 缺省 true = 地面层（空气且下方实心）；{@code top:false} = 天花板层
     * （空气且上方实心）。{@code min} 是绝对 y 的下限。
     *
     * <p>{@code top:false} 恒展开：上游没有「关掉天花板植被」这个概念，本移植早先那个
     * {@code flora.ceiling} 旋钮已删除。
     */
    record EveryLayer(int minY, int maxY, boolean top) implements PlacementModifier {
        EveryLayer(int minY, boolean top) {
            this(minY, Integer.MAX_VALUE, top);
        }

        @Override
        public void apply(List<Point> in, List<Point> out, WorldView world) {
            // 源码：min/max 都会夹到世界建造高度内。
            // **注意**：这里传的是 codec 的 `min` 原值，`+1`（循环下界）已经在
            // `Layers.emit` 内部做过一次；调用点再加偏移就是双重偏移，会把整层吃掉。
            int low = Math.max(minY, world.minY());
            int high = Math.min(maxY, world.maxY());
            for (Point point : in) {
                Layers.layers(world, point.x(), point.z(), low, high, top, out);
            }
        }

        /** 惰性版本：每扫到一层就立刻跑下游，扫完一层才继续下一层（上游 do-while consumer）。 */
        @Override
        public void visit(Point point, WorldView world, Consumer<Point> next) {
            // 同 apply：`min` 原值直传，`+1` 只在 Layers.emit 里做一次。
            int low = Math.max(minY, world.minY());
            int high = Math.min(maxY, world.maxY());
            Layers.emit(world, point.x(), point.z(), low, high, top, next);
        }
    }

    /** {@code minecraft:block_predicate_filter}：保留谓词成立的位置。 */
    record BlockPredicateFilter(BlockPredicates.Predicate predicate) implements PlacementModifier {
        @Override
        public void apply(List<Point> in, List<Point> out, WorldView world) {
            for (Point point : in) {
                if (predicate.test(world, point)) {
                    out.add(point);
                }
            }
        }
    }

    /** {@code wover:is}：谓词读的是「偏移之后」的那个方块。 */
    record Is(Point offset, BlockPredicates.Predicate predicate) implements PlacementModifier {
        @Override
        public void apply(List<Point> in, List<Point> out, WorldView world) {
            for (Point point : in) {
                if (predicate.test(world, point.offset(offset.x(), offset.y(), offset.z()))) {
                    out.add(point);
                }
            }
        }
    }

    /**
     * {@code wover:is_next_to}：{@code pos + offset} 的六个邻居里至少有一个匹配谓词。
     *
     * <p>语义假设（源码不在本仓库）：名字与用法都指向「紧邻」——上游只有一处用
     * {@code offset:[0,-1,0] + matching_fluids lava}，读作「脚下那块地面紧邻岩浆」，
     * 也就是芦苇长在岩浆岸边。实现为六邻域判定而不是某一固定方向，因为 {@code is_next_to}
     * 没有方向字段。
     */
    record IsNextTo(Point offset, BlockPredicates.Predicate predicate) implements PlacementModifier {
        @Override
        public void apply(List<Point> in, List<Point> out, WorldView world) {
            for (Point point : in) {
                Point anchor = point.offset(offset.x(), offset.y(), offset.z());
                // 源码 IsNextTo#shouldPlace 只测 xz 平面四邻（west/east/north/south），**不含上下**。
                for (Point.Direction dir : HORIZONTAL) {
                    if (predicate.test(world, anchor.offset(dir, 1))) {
                        out.add(point);
                        break;
                    }
                }
            }
        }
    }

    /**
     * {@code wover:solid_in_dir}：对应 wover 的 {@code FindInDirection}
     * （{@code feature/api/placed/modifiers/FindInDirection.java}，源码实读）。
     *
     * <p>与我的旧实现相差四处，全部按源码纠正：
     * <ul>
     *   <li>{@code dist} 缺省 <b>12</b>（原版 range 1..32），不是 16；</li>
     *   <li>{@code offset_in_dir} 缺省 <b>0</b>，且是**沿搜索方向再加**（正数往更远处），
     *       不是「回退」；</li>
     *   <li>{@code random_select} 缺省 <b>true</b>，含义是「<b>只搜一个随机方向</b>」
     *       （消耗 1 个 {@code nextInt(dirs.size())}），不是「在多个结果里随机挑一个」；</li>
     *   <li>{@code false} 时**每个命中的方向各 emit 一个位置**（不是只取第一个）。</li>
     * </ul>
     * 另外源码还有一个 {@code surface_predicate}（缺省 {@code BlockPredicates.ONLY_GROUND}），
     * 用它判定「找到的表面」；这里用实心谓词近似，已在 {@code WorldgenLoader} 里注明。
     */
    record FindInDirection(List<Point.Direction> dirs, int dist, int offsetInDir,
                           boolean randomSelect, BlockPredicates.Predicate surface)
            implements PlacementModifier {
        @Override
        public void apply(List<Point> in, List<Point> out, WorldView world) {
            for (Point point : in) {
                if (randomSelect) {
                    Point.Direction dir = dirs.get(world.random().nextInt(dirs.size()));
                    Point found = firstMatch(world, point, dir, dist, surface);
                    if (found != null) {
                        out.add(found.offset(dir, offsetInDir));
                    }
                    continue;
                }
                for (Point.Direction dir : dirs) {
                    Point found = firstMatch(world, point, dir, dist, surface);
                    if (found != null) {
                        out.add(found.offset(dir, offsetInDir));
                    }
                }
            }
        }

        private static Point firstMatch(WorldView world, Point from, Point.Direction dir, int dist,
                                        BlockPredicates.Predicate predicate) {
            for (int step = 1; step <= dist; step++) {
                Point probe = from.offset(dir, step);
                if (!world.inRegion(probe.x(), probe.y(), probe.z())) {
                    return null;
                }
                if (predicate.test(world, probe)) {
                    return probe;
                }
            }
            return null;
        }
    }

    /**
     * {@code wover:noise_filter}：照 wover {@code NoiseFilter#shouldPlace} 实现。
     *
     * <pre>
     * final Noise noise = ctx.getLevel().getLevel().getChunkSource().randomState().getOrCreateNoise(this.noise);
     * final double v = noise.get(pos.getX() * scaleXZ, pos.getY() * scaleY, pos.getZ() * scaleXZ);
     * return v &gt; minNoiseLevel &amp;&amp; v &lt; maxNoiseLevel;
     * </pre>
     * 缺省：{@code min=-MAX_VALUE}、{@code max=+MAX_VALUE}、{@code scale_xz=1f}、{@code scale_y=1f}。
     *
     * <p>噪声必须来自 level 的 {@code RandomState}（世界种子化、按世界缓存）。wover 的注释
     * 专门记录了这个坑：早先它用静态的 {@code NoiseParameterManager} 缓存，噪声场取决于
     * 「本进程哪个区块先被生成」，重启前后区块会沿接缝错位。
     */
    record NoiseFilter(String noiseId, double min, double max, double scaleXZ, double scaleY)
            implements PlacementModifier {
        @Override
        public void apply(List<Point> in, List<Point> out, WorldView world) {
            for (Point point : in) {
                double value = world.noise(noiseId,
                        point.x() * scaleXZ, point.y() * scaleY, point.z() * scaleXZ);
                // NaN（没有噪声实现）会自然判假，不会静默放行。
                if (value > min && value < max) {
                    out.add(point);
                }
            }
        }
    }

    /** {@code minecraft:offset}：xyz 三个方向各自用整数提供器采样。 */
    record Offset(IntProvider x, IntProvider y, IntProvider z) implements PlacementModifier {
        @Override
        public void apply(List<Point> in, List<Point> out, WorldView world) {
            for (Point point : in) {
                out.add(point.offset(x.sample(world.random()), y.sample(world.random()),
                        z.sample(world.random())));
            }
        }
    }

    /** {@code wover:offset}：固定偏移。 */
    record FixedOffset(Point shift) implements PlacementModifier {
        @Override
        public void apply(List<Point> in, List<Point> out, WorldView world) {
            for (Point point : in) {
                out.add(point.offset(shift.x(), shift.y(), shift.z()));
            }
        }
    }

    /**
     * {@code wover:all}：**把 16×16 网格里除最后一格外的 255 个位置全部 emit**
     * （{@code feature/api/placed/modifiers/All.java} 源码实读）。
     *
     * <pre>
     * for (int i = 0; i < 16 * 16 - 1; i++) consumer.accept(blockPos.offset(i & 0xF, 0, i >> 4));
     * </pre>
     * 我此前把它当成「空标记、原样透传」——那是错的：透传只 emit 1 个位置，而它 emit 255 个。
     * 当前 step 9 里没有任何 supported 特征用它（只有地形侧的 {@code flooded_lava_pit} 用），
     * 但语义必须按源码写，否则哪天它出现就会静默少放 254 份。
     */
    record EmitGrid() implements PlacementModifier {
        @Override
        public void apply(List<Point> in, List<Point> out, WorldView world) {
            for (Point point : in) {
                for (int i = 0; i < 16 * 16 - 1; i++) {
                    out.add(point.offset(i & 0xF, 0, i >> 4));
                }
            }
        }
    }

    /** 浮点提供器：{@code minecraft:constant} / {@code minecraft:uniform}（区间 [min, max)）。 */
    record Floats(float min, float max, boolean constant) {
        float sample(java.util.Random random) {
            return constant ? min : min + random.nextFloat() * (max - min);
        }
    }

    /**
     * {@code wover:stencil}：把输入位置平铺到 16×16 模板里所有为真的格子。
     *
     * <p>wover {@code Stencil#modify}（release/26.3）：x 外层、z 内层；
     * 命中格子后仅当 {@code one_in > 1} 才消耗一次 {@code nextInt(one_in)}。
     */
    record Stencil(boolean[] structures, int oneIn) implements PlacementModifier {
        @Override
        public void apply(List<Point> in, List<Point> out, WorldView world) {
            for (Point point : in) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        if (!structures[(x << 4) | z]) {
                            continue;
                        }
                        if (oneIn > 1 && world.random().nextInt(oneIn) != 0) {
                            continue;
                        }
                        out.add(point.offset(x, 0, z));
                    }
                }
            }
        }
    }

    /**
     * {@code wover:is_basin}：下/西/东/北/南 全部匹配才通过；有 {@code top_predicate} 时再判上方。
     *
     * <p>wover {@code IsBasin#shouldPlace}；不消耗随机数。
     */
    record IsBasin(BlockPredicates.Predicate predicate, BlockPredicates.Predicate top)
            implements PlacementModifier {
        @Override
        public void apply(List<Point> in, List<Point> out, WorldView world) {
            for (Point point : in) {
                if (top != null && !top.test(world, point.offset(Point.Direction.UP, 1))) {
                    continue;
                }
                if (predicate.test(world, point.offset(Point.Direction.DOWN, 1))
                        && predicate.test(world, point.offset(Point.Direction.WEST, 1))
                        && predicate.test(world, point.offset(Point.Direction.EAST, 1))
                        && predicate.test(world, point.offset(Point.Direction.NORTH, 1))
                        && predicate.test(world, point.offset(Point.Direction.SOUTH, 1))) {
                    out.add(point);
                }
            }
        }
    }

    /**
     * {@code betternether:avoid_nether_city}：特征可能写到的范围里有下界城市就**丢弃**这个位置。
     *
     * <p>上游 {@code AvoidNetherCity#shouldPlace}：
     * {@code include_cave ? !CityStructure.cityWithin(level, origin, reach) : !CityStructure.buildingsWithin(...)}，
     * 也就是「够得着城市就不要」。它给那些自己不会查结构的特征用（{@code wover:template} 的
     * 倒挂林落叶）：这些落叶在 {@code SURFACE_STRUCTURES} 放，早于城市，于是相邻区块的城市一旦
     * 先长好，落叶就落进人家的房间和街道里。
     *
     * <p>两个参数的语义：
     * <ul>
     *   <li>{@code reach} —— 特征从这个位置最多能横向写出多远（上游 {@code 0..64}）；</li>
     *   <li>{@code include_cave} —— 连城市的洞穴一起避开。本移植的城市是数据包 jigsaw，其 piece
     *       全部属于城市本身，没有单独的洞穴 piece，因此两种取值判的是同一批 piece
     *       （见 {@link WorldView#netherCityWithin}）。</li>
     * </ul>
     *
     * <p>**问不出来时按「在城里」处理**（丢弃 + 记账），与 {@code noise_filter} 把 {@code NaN}
     * 判假同一口径：宁可少放落叶，也不要静默把整棵树塞进建筑里。
     */
    record AvoidNetherCity(int reach, boolean includeCave) implements PlacementModifier {
        @Override
        public void apply(List<Point> in, List<Point> out, WorldView world) {
            for (Point point : in) {
                Boolean city = world.netherCityWithin(point.x(), point.z(), reach, includeCave);
                if (city == null) {
                    WorldgenWarnings.degrade("modifier:avoid_nether_city",
                            "WorldView 问不到结构 starts，无法判定是否落在下界城里：按「在城里」丢弃该位置");
                    continue;
                }
                if (!city) {
                    out.add(point);
                }
            }
        }
    }

    /** {@code wover:extend}：原位 + 沿方向 1..count（{@code count = length.sample}）。 */
    record Extend(Point.Direction direction, IntProvider length) implements PlacementModifier {
        @Override
        public void apply(List<Point> in, List<Point> out, WorldView world) {
            for (Point point : in) {
                int count = length.sample(world.random());
                out.add(point);
                for (int step = 1; step < count + 1; step++) {
                    out.add(point.offset(direction, step));
                }
            }
        }
    }

    /**
     * {@code wover:extend_xyz}：以 radius 在 xz 铺开，按 center/border 密度线性插值逐格
     * {@code nextFloat} 判定，再按 {@code height_propagation} 往 y 扩展。
     *
     * <p>随机数顺序照 wover {@code ExtendXYZ#modify}：radius → center → border → height，
     * 然后逐格（x 外层、z 内层）。
     */
    record ExtendXYZ(IntProvider radius, Floats centerDensity, Floats borderDensity, boolean square,
                     Floats height, String heightPropagation) implements PlacementModifier {
        @Override
        public void apply(List<Point> in, List<Point> out, WorldView world) {
            for (Point point : in) {
                int r = radius.sample(world.random());
                float r2 = (float) r * r;
                float d0 = centerDensity.sample(world.random());
                float d1 = borderDensity.sample(world.random());
                int heightInt = (int) (r * height.sample(world.random()));
                int scale = heightPropagation.endsWith("_down") ? -1 : 1;
                for (int x = -r; x <= r; x++) {
                    for (int z = -r; z <= r; z++) {
                        float currentR2 = x * x + z * z;
                        if (!square && currentR2 > r2) {
                            continue;
                        }
                        float lambda = currentR2 / r2;
                        float density = d0 + (d1 - d0) * lambda;
                        Point now = point.offset(x, 0, z);
                        boolean added = density >= 1.0F
                                || (density > 0.001F && density >= world.random().nextFloat());
                        if (added) {
                            out.add(now);
                        }
                        switch (heightPropagation) {
                            case "box_down", "box_up" ->
                                    propagate(world, out, d0, d1, density, now, heightInt, scale);
                            case "sphere_down", "sphere_up" -> {
                                int depth = (int) (heightInt * (1 - (currentR2 / r2)));
                                propagate(world, out, d0, d1, density, now, depth, scale);
                            }
                            case "spikes_down", "spikes_up" -> {
                                if (added) {
                                    propagateConnected(world, out, d0, d1, density * density, now,
                                            heightInt, scale);
                                }
                            }
                            default -> {
                                // none
                            }
                        }
                    }
                }
            }
        }

        private static void propagate(WorldView world, List<Point> out, float d0, float d1,
                                      float currentDensity, Point pos, int depth, int scale) {
            float depth2 = (float) depth * depth;
            for (int y = 1; y <= depth; y++) {
                float lambda = (float) (y * y) / depth2;
                float densityDown = (d0 + (d1 - d0) * lambda) * currentDensity;
                if (densityDown <= 0.001F) {
                    continue;
                }
                if (world.random().nextFloat() > densityDown) {
                    continue;
                }
                out.add(pos.offset(0, y * scale, 0));
            }
        }

        private static void propagateConnected(WorldView world, List<Point> out, float d0, float d1,
                                               float currentDensity, Point pos, int depth, int scale) {
            float depth2 = (float) depth * depth;
            for (int y = 1; y <= depth; y++) {
                float lambda = (float) (y * y) / depth2;
                float densityDown = (d0 + (d1 - d0) * lambda) * currentDensity;
                if (densityDown <= 0.001F) {
                    return;
                }
                if (world.random().nextFloat() > densityDown) {
                    return;
                }
                out.add(pos.offset(0, y * scale, 0));
            }
        }
    }

    /** 小工具：夹取。 */
    final class Constants {
        private Constants() {
        }

        static int clamp(int value, int min, int max) {
            return value < min ? min : Math.min(value, max);
        }
    }
}
