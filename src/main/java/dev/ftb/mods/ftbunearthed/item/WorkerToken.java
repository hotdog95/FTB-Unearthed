package dev.ftb.mods.ftbunearthed.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.ftb.mods.ftbunearthed.FTBUnearthedTags;
import dev.ftb.mods.ftbunearthed.config.ServerConfig;
import dev.ftb.mods.ftbunearthed.registry.ModDataComponents;
import dev.ftb.mods.ftbunearthed.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

public class WorkerToken extends Item {
    public WorkerToken(Properties properties) {
        super(properties);
    }

    // 4x the level thresholds for villager trading xp - see VillagerData#NEXT_LEVEL_XP_THRESHOLDS
    private static final int[] WORKER_XP_TABLE = new int[] {
            0,   // start of level 1
            40,  // total XP to get to level 2
            280, // .. 3
            600, // .. 4
            1000 // .. 5
    };

    public static void addTooltip(ItemTooltipEvent event) {
        if (event.getItemStack().is(FTBUnearthedTags.Items.WORKER_TOKENS)) {
            WorkerData data = getWorkerData(event.getItemStack());
            if (!data.hideTooltip) {
                List<Component> toolTip = event.getToolTip();
                toolTip.add(tooltipLine("worker_profession", data.getProfessionName()));
                toolTip.add(tooltipLine("worker_type", data.getVillagerTypeName()));
                toolTip.add(tooltipLine("worker_level", Component.translatable("merchant.level." + data.getVillagerLevel()).append(" (" + data.getVillagerLevel() + ")")));
                int progress = getXPProgress(event.getItemStack());
                if (progress > 0 && progress < 100) {
                    toolTip.add(tooltipLine("worker_xp_progress", Integer.toString(progress)));
                }
            }
        }
    }

    public static Component tooltipLine(String what, MutableComponent value) {
        return Component.translatable("ftbunearthed.tooltip." + what, value.withStyle(ChatFormatting.AQUA))
                .withStyle(ChatFormatting.YELLOW);
    }

    public static Component tooltipLine(String what, String value) {
        return tooltipLine(what, Component.literal(value));
    }

    public static void setWorkerData(ItemStack token, WorkerData data) {
        token.set(ModDataComponents.WORKER_DATA, data);
    }

    public static ItemStack createWithData(WorkerData data) {
        return Util.make(new ItemStack(ModItems.WORKER_TOKEN.get()), s -> setWorkerData(s, data));
    }

    @Override
    public Component getName(ItemStack stack) {
        Component defName = super.getName(stack);
        WorkerData data = getWorkerData(stack);
        return defName.copy().append(" (").append(data.getProfessionName()).append(")");
    }

    public static Optional<WorkerData> getOptionalWorkerData(ItemStack stack) {
        return Optional.ofNullable(stack.get(ModDataComponents.WORKER_DATA));
    }

    public static WorkerData getWorkerData(ItemStack stack) {
        return getOptionalWorkerData(stack).orElse(WorkerData.UNEMPLOYED);
    }

    public static boolean addWorkerXP(ItemStack stack, int amount) {
        WorkerData data = getWorkerData(stack);
        int lvl = data.getVillagerLevel();

        if (data.profession == VillagerProfession.NONE || lvl >= 5) {
            return false;
        }

        int currentXP = getWorkerXP(stack, data);
        int neededXP = WORKER_XP_TABLE[data.getVillagerLevel()];
        int newXP = currentXP + amount;

        boolean levelUp = false;
        if (currentXP < neededXP && newXP >= neededXP) {
            levelUp = true;
            setWorkerData(stack, new WorkerData(data.profession, data.type, Optional.of(data.getVillagerLevel() + 1), data.hideTooltip));
        }
        stack.set(ModDataComponents.WORKER_XP, newXP);

        return levelUp;
    }

    public static int getXPProgress(ItemStack stack) {
        WorkerData data = getWorkerData(stack);
        int lvl = data.getVillagerLevel();
        int currentXP = getWorkerXP(stack, data);
        if (data.profession == VillagerProfession.NONE) {
            return 0;
        }
        if (data.getVillagerLevel() >= 5) {
            return 100;
        }
        int thisLvl = WORKER_XP_TABLE[lvl - 1];
        int nextLvl = WORKER_XP_TABLE[lvl];

        return (currentXP - thisLvl) * 100 / (nextLvl - thisLvl);
    }

    private static int getWorkerXP(ItemStack stack, WorkerData data) {
        Integer xp = stack.get(ModDataComponents.WORKER_XP.get());
        return Objects.requireNonNullElseGet(xp, () -> WORKER_XP_TABLE[data.getVillagerLevel() - 1]);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (context.getLevel() instanceof ServerLevel level && player != null) {
            BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
            Villager villager = new Villager(EntityType.VILLAGER, level);
            villager.setPos(Vec3.atBottomCenterOf(pos));
            villager.lookAt(EntityAnchorArgument.Anchor.EYES, player.getEyePosition());
            level.addFreshEntity(villager);
            WorkerData workerData = getWorkerData(context.getItemInHand());
            // if we set villager xp to 0, worker data gets immediately reset by dumb villager brain
            villager.setVillagerXp(Math.max(0, VillagerData.getMinXpPerLevel(workerData.getVillagerLevel()))); //You have a config to preserve villager level. Setting xp to 1 means you cannot cycle trades on an unleveled villager
            villager.setVillagerData(workerData.toVillagerData());
            level.playSound(null, villager.blockPosition(), SoundEvents.ENDER_PEARL_THROW, SoundSource.PLAYERS, 1f, 1f);
            Vec3 vec = villager.getPosition(1f).add(0, 0, 0);
            level.sendParticles(ParticleTypes.PORTAL, vec.x, vec.y, vec.z, 50, 0.2, 0.2, 0.2, 0.1);

            if (!player.isCreative()) {
                player.getItemInHand(context.getHand()).shrink(1);
            }
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    // can't use VillagerData, sadly, because it doesn't override equals() and hashCode()
    public record WorkerData(VillagerProfession profession, Optional<VillagerType> type, Optional<Integer> level, boolean hideTooltip)
            implements Predicate<ItemStack>
    {
        public static final Codec<WorkerData> COMPONENT_CODEC = RecordCodecBuilder.create(builder -> builder.group(
                        BuiltInRegistries.VILLAGER_PROFESSION.byNameCodec()
                                .fieldOf("profession")
                                .forGetter(WorkerData::profession),
                        BuiltInRegistries.VILLAGER_TYPE.byNameCodec()
                                .optionalFieldOf("type")
                                .forGetter(WorkerData::type),
                        Codec.INT.optionalFieldOf("level").forGetter(WorkerData::level),
                        Codec.BOOL.optionalFieldOf("hide_tooltip", false).forGetter(WorkerData::hideTooltip)
                )
                .apply(builder, WorkerData::new));

        public static final StreamCodec<RegistryFriendlyByteBuf, WorkerData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.registry(Registries.VILLAGER_PROFESSION), WorkerData::profession,
                ByteBufCodecs.optional(ByteBufCodecs.registry(Registries.VILLAGER_TYPE)), WorkerData::type,
                ByteBufCodecs.optional(ByteBufCodecs.VAR_INT), WorkerData::level,
                ByteBufCodecs.BOOL, WorkerData::hideTooltip,
                WorkerData::new
        );
        public static final WorkerData UNEMPLOYED = new WorkerData(VillagerProfession.NONE, 1);

        public WorkerData(VillagerProfession profession) {
            this(profession, Optional.empty(), Optional.empty(), false);
        }

        public WorkerData(VillagerProfession profession, VillagerType type) {
            this(profession, Optional.of(type), Optional.empty(), false);
        }

        public WorkerData(VillagerProfession profession, int level) {
            this(profession, Optional.empty(), Optional.of(level), false);
        }

        public static WorkerData fromVillagerData(VillagerData data) {
            Optional<Integer> lvl = ServerConfig.ENCODER_KEEPS_VILLAGER_LEVEL.get() ?
                    Optional.of(data.getLevel()) :
                    Optional.empty();
            VillagerType type = ServerConfig.encodedVillagerType().orElse(data.getType());
            return new WorkerData(data.getProfession(), Optional.of(type), lvl, false);
        }

        public WorkerData hideTooltip(boolean hide) {
            return new WorkerData(profession, type, level, hide);
        }

        @Override
        public boolean test(ItemStack workerStack) {
            return WorkerToken.getOptionalWorkerData(workerStack).map(data -> {
                if (!data.profession.equals(this.profession)) return false;
                if (this.type.isPresent() && !data.type.equals(this.type)) return false;
                return data.getVillagerLevel() >= this.getVillagerLevel();
            }).orElse(false);
        }

        public int getVillagerLevel() {
            return level().orElse(1);
        }

        public VillagerData toVillagerData() {
            return new VillagerData(type.orElse(VillagerType.PLAINS), profession, getVillagerLevel());
        }

        public MutableComponent getProfessionName() {
            return Component.translatable("entity.minecraft.villager." + profession.toString());
        }

        public MutableComponent getVillagerTypeName() {
            var id = BuiltInRegistries.VILLAGER_TYPE.getKey(type().orElse(VillagerType.PLAINS));
            return Component.translatable("ftbunearthed.villager_type." + id.toShortLanguageKey());
        }
    }
}
