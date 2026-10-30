package dev.mumu.betternether.worldgen;

import org.jetbrains.annotations.NotNull;

/**
 * 一个方块坐标。不可变，可安全地放进列表/集合。
 *
 * <p>放置管线的每一步都在变换位置，用 record 而不是可变对象是为了让「修饰符只能读入参、
 * 写写出参」这条约定在类型上就成立 —— 上一轮的位置绝不会被下一步悄悄改掉，这正是
 * 上游 {@code Stream<BlockPos>} 语义里的关键点。
 */
public record Point(int x, int y, int z) {
    public @NotNull Point offset(int dx, int dy, int dz) {
        return new Point(x + dx, y + dy, z + dz);
    }

    public @NotNull Point offset(Direction direction, int distance) {
        return new Point(x + direction.dx() * distance, y + direction.dy() * distance,
                z + direction.dz() * distance);
    }

    /** 原版 {@code Direction} 的六个值，只需要偏移量。 */
    public enum Direction {
        DOWN(0, -1, 0),
        UP(0, 1, 0),
        NORTH(0, 0, -1),
        SOUTH(0, 0, 1),
        WEST(-1, 0, 0),
        EAST(1, 0, 0);

        private final int dx;
        private final int dy;
        private final int dz;

        Direction(int dx, int dy, int dz) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }

        public int dx() {
            return dx;
        }

        public int dy() {
            return dy;
        }

        public int dz() {
            return dz;
        }

        public Direction opposite() {
            return switch (this) {
                case DOWN -> UP;
                case UP -> DOWN;
                case NORTH -> SOUTH;
                case SOUTH -> NORTH;
                case WEST -> EAST;
                case EAST -> WEST;
            };
        }

        /** JSON 里的小写名字（{@code "north"} / {@code "down"} …）。 */
        public static Direction byJsonName(String name) {
            return switch (name.toLowerCase(java.util.Locale.ROOT)) {
                case "down" -> DOWN;
                case "up" -> UP;
                case "north" -> NORTH;
                case "south" -> SOUTH;
                case "west" -> WEST;
                case "east" -> EAST;
                default -> throw new IllegalArgumentException("未知方向：" + name);
            };
        }
    }
}
