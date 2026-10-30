package dev.mumu.betternether.entity;

import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EntityType;

/**
 * The six BetterNether mobs, expressed as <b>vanilla carriers</b> instead of new entity types.
 *
 * <h2>Why a carrier and not a custom entity</h2>
 * Neither Paper nor CraftEngine can register a new {@code EntityType}. CraftEngine 26.10 does have an
 * {@code entity:} config section, but its real schema (read from the installed jar's bytecode, not from
 * docs) is only {@code settings.attributes} and {@code settings.tags} on an id supplied by an external
 * {@code EntityProvider}; there is no carrier, hitbox, model, AI or spawn-rule field. So a custom
 * {@code betternether:firefly} cannot exist, and this enum is where that limit is made explicit: each
 * species names a vanilla {@link EntityType} whose <em>hitbox and movement</em> are closest to the
 * upstream mob, plus the upstream attribute values to apply on spawn.
 *
 * <p>What is <b>not</b> reproduced: the model, the AI goals, the custom projectile and the custom
 * particles. A firefly is an allay, and it looks like one. {@link #note()} states the trade-off per
 * species so nobody has to guess which parts are faithful.
 *
 * <h2>Attributes</h2>
 * Values are the ones upstream actually sets in each {@code createMobAttributes()}, not estimates.
 * Only attributes that exist on the carrier are applied; {@link CarrierSpawner} skips the rest rather
 * than throwing, because e.g. an allay has no attack damage.
 */
public enum CarrierSpecies {

    /** 0.5x0.5 ambient flyer that glows. */
    FIREFLY("firefly", EntityType.ALLAY, true, true,
            null,
            new AttributeValue(Attribute.MAX_HEALTH, 1.0),
            new AttributeValue(Attribute.MOVEMENT_SPEED, 0.25),
            new AttributeValue(Attribute.FOLLOW_RANGE, 48.0)),

    /** 2x5 floating nether ambient; ghast is the only carrier that is big, flying and fire immune. */
    HYDROGEN_JELLYFISH("hydrogen_jellyfish", EntityType.GHAST, false, true,
            null,
            new AttributeValue(Attribute.MAX_HEALTH, 0.5),
            new AttributeValue(Attribute.MOVEMENT_SPEED, 0.5),
            new AttributeValue(Attribute.ATTACK_DAMAGE, 20.0)),

    /** Ranged nether monster; blaze keeps the ranged attack and the fire immunity. */
    NAGA("naga", EntityType.BLAZE, false, false,
            null,
            new AttributeValue(Attribute.MAX_HEALTH, 10.0),
            new AttributeValue(Attribute.FOLLOW_RANGE, 35.0),
            new AttributeValue(Attribute.MOVEMENT_SPEED, 0.23),
            new AttributeValue(Attribute.ATTACK_DAMAGE, 3.0)),

    /** Keeps the pig identity and body size; flight has no vanilla carrier. */
    FLYING_PIG("flying_pig", EntityType.PIG, true, false,
            null,
            new AttributeValue(Attribute.MAX_HEALTH, 6.0),
            new AttributeValue(Attribute.FOLLOW_RANGE, 12.0),
            new AttributeValue(Attribute.MOVEMENT_SPEED, 0.3),
            new AttributeValue(Attribute.ATTACK_DAMAGE, 3.0)),

    /** Upstream subclasses vanilla Skeleton, so a skeleton variant is the faithful carrier. */
    JUNGLE_SKELETON("jungle_skeleton", EntityType.BOGGED, true, false,
            null,
            new AttributeValue(Attribute.MAX_HEALTH, 20.0),
            new AttributeValue(Attribute.MOVEMENT_SPEED, 0.25)),

    /** Small flying hostile; vex is the only vanilla carrier of that shape. */
    SKULL("skull", EntityType.VEX, true, false,
            null,
            new AttributeValue(Attribute.MAX_HEALTH, 4.0),
            new AttributeValue(Attribute.FOLLOW_RANGE, 20.0),
            new AttributeValue(Attribute.MOVEMENT_SPEED, 0.5),
            new AttributeValue(Attribute.ATTACK_DAMAGE, 1.0));

    /** One attribute value from the upstream {@code createMobAttributes()} call. */
    public record AttributeValue(Attribute attribute, double value) {
    }

    private final String upstreamId;
    private final EntityType carrier;
    private final boolean needsFireResistance;
    private final boolean glowing;
    private final String ambientSound;
    private final AttributeValue[] attributes;

    CarrierSpecies(
            String upstreamId,
            EntityType carrier,
            boolean needsFireResistance,
            boolean glowing,
            String ambientSound,
            AttributeValue... attributes
    ) {
        this.upstreamId = upstreamId;
        this.carrier = carrier;
        this.needsFireResistance = needsFireResistance;
        this.glowing = glowing;
        this.ambientSound = ambientSound;
        this.attributes = attributes;
    }

    /** Upstream id, e.g. {@code firefly} (namespace {@code betternether}). */
    public String upstreamId() {
        return upstreamId;
    }

    /** The vanilla entity type that stands in for it. */
    public EntityType carrier() {
        return carrier;
    }

    /** Translation key for the upstream display name ({@code entity.betternether.<id>}). */
    public String displayNameKey() {
        return "entity.betternether." + upstreamId;
    }

    /**
     * Whether the carrier needs a fire-resistance effect to approximate upstream's {@code fireImmune()}.
     * Ghast and blaze are already immune; the others are not.
     */
    public boolean needsFireResistance() {
        return needsFireResistance;
    }

    /** Upstream mobs are all fire immune; a glowing carrier stands in for the emissive ones. */
    public boolean glowing() {
        return glowing;
    }

    /** 本包自定义音效已按用户要求停发，所有物种返回 null；保留原版载体本身的声音。 */
    public String ambientSound() {
        return ambientSound;
    }

    public AttributeValue[] attributes() {
        return attributes.clone();
    }

    /** The trade-off this carrier makes, in one line. */
    public String note() {
        return switch (this) {
            case FIREFLY -> "allay 的碰撞箱与飞行姿态最接近；外观仍是悦灵，不冒充萤火虫模型";
            case HYDROGEN_JELLYFISH -> "大体、漂浮、下界出身、火焰免疫四项全中；代价是它会发射火球";
            case NAGA -> "同为下界远程、火焰免疫，碰撞箱接近；投射物是火球而非 naga_projectile";
            case FLYING_PIG -> "只保住猪的身份与体型；飞行没有载体，不额外加飘浮（会卡天花板）";
            case JUNGLE_SKELETON -> "上游继承原版 Skeleton，BOGGED 碰撞箱 0.6x1.99 一致";
            case SKULL -> "原版唯一同形态飞行敌对单位；vex 持剑近战，上游是撞击伤害";
        };
    }

    /** Looks a species up by upstream id; {@code null} when the id is not ported. */
    public static CarrierSpecies fromUpstreamId(String upstreamId) {
        for (CarrierSpecies species : values()) {
            if (species.upstreamId.equals(upstreamId)) {
                return species;
            }
        }
        return null;
    }
}
