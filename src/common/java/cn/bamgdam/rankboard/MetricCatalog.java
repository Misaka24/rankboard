package cn.bamgdam.rankboard;

import java.util.Set;

/** Mapping-independent vanilla statistic groups used by aggregate leaderboards. */
final class MetricCatalog {
    private MetricCatalog() { }

    static final Set<String> TRAVEL_CUSTOM_STATS = Set.of(
            "minecraft:walk_one_cm", "minecraft:sprint_one_cm", "minecraft:crouch_one_cm",
            "minecraft:swim_one_cm", "minecraft:walk_on_water_one_cm", "minecraft:walk_under_water_one_cm",
            "minecraft:climb_one_cm", "minecraft:fly_one_cm", "minecraft:boat_one_cm",
            "minecraft:minecart_one_cm", "minecraft:horse_one_cm", "minecraft:pig_one_cm",
            "minecraft:strider_one_cm", "minecraft:aviate_one_cm", "minecraft:happy_ghast_one_cm",
            "minecraft:nautilus_one_cm");

    static final Set<String> ORE_BLOCKS = Set.of(
            "minecraft:coal_ore", "minecraft:deepslate_coal_ore",
            "minecraft:copper_ore", "minecraft:deepslate_copper_ore",
            "minecraft:iron_ore", "minecraft:deepslate_iron_ore",
            "minecraft:gold_ore", "minecraft:deepslate_gold_ore", "minecraft:nether_gold_ore",
            "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore",
            "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore",
            "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore",
            "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
            "minecraft:nether_quartz_ore", "minecraft:ancient_debris");

    static final Set<String> ZOMBIE_KILLERS = Set.of(
            "minecraft:zombie", "minecraft:husk", "minecraft:drowned",
            "minecraft:zombie_villager", "minecraft:zombified_piglin");

    static final Set<String> SKELETON_KILLERS = Set.of(
            "minecraft:skeleton", "minecraft:stray", "minecraft:bogged", "minecraft:wither_skeleton");

    static final Set<String> CREEPER_KILLERS = Set.of("minecraft:creeper");

    static final Set<String> ARTHROPOD_KILLERS = Set.of(
            "minecraft:spider", "minecraft:cave_spider", "minecraft:silverfish", "minecraft:endermite");

    static final Set<String> RAIDER_KILLERS = Set.of(
            "minecraft:pillager", "minecraft:vindicator", "minecraft:evoker",
            "minecraft:vex", "minecraft:ravager", "minecraft:illusioner");

    static final Set<String> NETHER_KILLERS = Set.of(
            "minecraft:blaze", "minecraft:ghast", "minecraft:magma_cube", "minecraft:hoglin",
            "minecraft:zoglin", "minecraft:piglin", "minecraft:piglin_brute",
            "minecraft:wither_skeleton", "minecraft:wither");

    static final Set<String> END_KILLERS = Set.of(
            "minecraft:enderman", "minecraft:endermite", "minecraft:shulker", "minecraft:ender_dragon");

    static final Set<String> WARDEN_KILLERS = Set.of("minecraft:warden");
}
