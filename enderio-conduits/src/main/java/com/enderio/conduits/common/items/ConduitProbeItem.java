package com.enderio.conduits.common.items;

import com.enderio.conduits.api.Conduit;
import com.enderio.conduits.api.EnderIOConduitsRegistries;
import com.enderio.conduits.api.connection.config.ConnectionConfig;
import com.enderio.conduits.api.connection.config.ConnectionConfigType;
import com.enderio.conduits.common.conduit.bundle.ConduitBundleBlockEntity;
import com.enderio.conduits.common.init.ConduitComponents;
import com.enderio.conduits.common.util.InteractionUtil;
import com.enderio.core.common.util.TooltipUtil;
import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntFunction;

import static com.enderio.conduits.common.init.ConduitComponents.CONDUIT_PROBE_STATE;

public class ConduitProbeItem extends Item {

    public static final Codec<ConduitConfigs> CODEC = Codec
        .lazyInitialized(() -> Codec.dispatchedMap(EnderIOConduitsRegistries.CONDUIT_CONNECTION_CONFIG_TYPE.byNameCodec(), type -> {
            //noinspection unchecked
            return (Codec<ConnectionConfig>) type.codec().codec();
        }))
        .xmap(ConduitConfigs::new, ConduitConfigs::byType);

    public static final StreamCodec<RegistryFriendlyByteBuf, ConduitConfigs> STREAM_CODEC = ByteBufCodecs.fromCodecWithRegistries(CODEC);

    private static final Logger log = LoggerFactory.getLogger(ConduitProbeItem.class);

    public ConduitProbeItem(Properties properties) {
        super(properties);
    }

    public static State getState(ItemStack stack) {
        return Optional.ofNullable(stack.get(CONDUIT_PROBE_STATE)).orElse(State.COPY_PASTE);
    }

    public static void setState(ItemStack stack, State state) {
        stack.set(CONDUIT_PROBE_STATE, state);
    }

    public static void switchState(ItemStack stack) {
        var state = getState(stack);
        var newState = (state.getId() + 1) % State.values().length;
        setState(stack, State.byId(newState));
    }

    @Override
    public @NotNull InteractionResult onItemUseFirst(@NotNull ItemStack stack, UseOnContext context) {
        var block = context.getLevel().getBlockEntity(context.getClickedPos());
        if (block instanceof ConduitBundleBlockEntity conduitBundle) {
            var connection = conduitBundle.getShape().getConnectionFromHit(context.getClickedPos(), context.getHitResult());
            if (connection == null)
                return InteractionResult.SUCCESS;
            if (context.getLevel().isClientSide())
                return InteractionResult.SUCCESS;
            switch (getState(stack)) {
            case COPY_PASTE -> {
                if (context.isSecondaryUseActive()) {
                    handleCopy(conduitBundle, InteractionUtil.fromClickLocation(context.getClickLocation(), context.getClickedPos().getCenter()), stack);
                } else {
                    handlePaste(conduitBundle, InteractionUtil.fromClickLocation(context.getClickLocation(), context.getClickedPos().getCenter()), stack);
                }
            }
            case PROBE -> {
                // TODO
                context.getPlayer().sendSystemMessage(Component.literal("This feature isn't implemented yet.").withStyle(ChatFormatting.RED));
            }
            }
            return InteractionResult.SUCCESS;
        }
        return super.onItemUseFirst(stack, context);
    }

    private void handleCopy(ConduitBundleBlockEntity conduitBundle, Direction face, ItemStack itemStack) {
        var configs = new ConduitConfigs();

        for (var conduit : conduitBundle.getConduits()) {
            var type = conduit.value().connectionConfigType();
            var config = conduitBundle.getConnectionConfig(conduit, face);
            configs.byType().put(type, config);
        }

        itemStack.set(ConduitComponents.CONDUIT_PROBE_CONFIG, configs);
    }

    public void handlePaste(ConduitBundleBlockEntity conduitBundle, Direction face, ItemStack itemStack) {
        var configs = itemStack.get(ConduitComponents.CONDUIT_PROBE_CONFIG);
        if (configs == null) {
            return;
        }

        for (var conduit : conduitBundle.getConduits()) {
            var type = conduit.value().connectionConfigType();
            var config = configs.byType().get(type);
            if (config == null) {
                continue;
            }
            conduitBundle.setConnectionConfig(conduit, face, config);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, @NotNull TooltipContext context, @NotNull List<Component> tooltipComponents, @NotNull TooltipFlag isAdvanced) {
        if (!(stack.getItem() instanceof ConduitProbeItem)) {
            return;
        }
        var builder = new StringBuilder();
        for (var s : ConduitProbeItem.getState(stack).toString().toLowerCase().split("_")) {
            builder.append(StringUtils.capitalize(s));
            builder.append(" ");
        }
        builder.deleteCharAt(builder.length() - 1);
        tooltipComponents.add(TooltipUtil.style(Component.translatable("tooltip.enderio.conduit_probe.mode", builder.toString())));

        super.appendHoverText(stack, context, tooltipComponents, isAdvanced);
    }

    public enum State implements StringRepresentable {
        PROBE(0, "probe"), COPY_PASTE(1, "copy_paste");

        public static final Codec<State> CODEC = StringRepresentable.fromEnum(State::values);

        public static final StreamCodec<ByteBuf, State> STREAM_CODEC = ByteBufCodecs.idMapper(State::byId, State::getId);

        private static final IntFunction<State> BY_ID = ByIdMap.continuous(State::getId, values(), ByIdMap.OutOfBoundsStrategy.CLAMP);

        private final int id;
        private final String name;

        State(int id, String name) {
            this.id = id;
            this.name = name;
        }

        public int getId() {
            return this.id;
        }

        public static State byId(int id) {
            return BY_ID.apply(id);
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }

    public record ConduitConfigs(Map<ConnectionConfigType<?>, ConnectionConfig> byType) {
        public ConduitConfigs() {
            this(new HashMap<>());
        }
    }

    public static void main(String[] args) {
    }

    public record DispatchedMapStreamCodec<B extends ByteBuf, K, V>(StreamCodec<B, K> keyCodec, Function<K, StreamCodec<B, V>> valueCodecFunction)
        implements StreamCodec<B, Map<K, V>> {

        @Override
        public Map<K, V> decode(B buf) {
            var i = ByteBufCodecs.readCount(buf, Integer.MAX_VALUE);
            var m = new Object2ObjectArrayMap<K, V>(i);

            for (var j = 0; j < i; ++j) {
                var k = keyCodec.decode(buf);
                var v = valueCodecFunction.apply(k).decode(buf);
                m.put(k, v);
            }

            return m;
        }

        @Override
        public void encode(B buf, Map<K, V> map) {
            ByteBufCodecs.writeCount(buf, map.size(), Integer.MAX_VALUE);
            map.forEach((k, v) -> {
                keyCodec.encode(buf, k);
                valueCodecFunction.apply(k).encode(buf, v);
            });
        }
    }
}
