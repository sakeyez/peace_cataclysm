package cn.sake_yez.culinarycataclysm.chapter.ignis;

import cn.sake_yez.culinarycataclysm.competition.CompetitionRecord;
import cn.sake_yez.culinarycataclysm.competition.CompetitionState;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

public final class IgnisCommands {
    private final IgnisBuildController buildController;

    public IgnisCommands(IgnisBuildController buildController) {
        this.buildController = buildController;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("culinarycompetition")
                .then(Commands.literal("resume").executes(this::resume))
                .then(Commands.literal("cancel").executes(this::cancel))
                .then(Commands.literal("end").executes(this::cancel))
                .then(Commands.literal("status").executes(this::status))
                .then(Commands.literal("diagnose").executes(IgnisDiagnostics::diagnose)));
    }

    private int resume(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        IgnisChallengeRegistry data = IgnisChallengeRegistry.get(source.getLevel());
        Optional<CompetitionRecord> optional = data.byPlayer(player.getUUID());
        if (optional.isEmpty()) {
            source.sendFailure(Component.translatable("message.culinary_cataclysm.no_active"));
            return 0;
        }
        CompetitionRecord record = optional.get();
        if (record.state == CompetitionState.INTERRUPTED) {
            // 料理事务中断后不盲目重放投料；恢复原状态、锅具和账本，不再次配给原料。
            record.state = record.interruptedState == CompetitionState.IDLE
                    ? (record.kitchensBuilt ? CompetitionState.ROUND_ACTIVE : CompetitionState.BUILDING)
                    : record.interruptedState;
            record.interruptedState = CompetitionState.IDLE;
            record.stateTicks = 0;
            data.changed(record);
        }
        source.sendSuccess(() -> IgnisPresentation.statusText(record), false);
        return 1;
    }

    private int cancel(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return 0;
        IgnisChallengeRegistry data = IgnisChallengeRegistry.get(player.serverLevel());
        Optional<CompetitionRecord> optional = data.byPlayer(player.getUUID());
        if (optional.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("message.culinary_cataclysm.no_active"));
            return 0;
        }
        CompetitionRecord record = optional.get();
        if (record.state != CompetitionState.CLEANUP
                && !(record.state == CompetitionState.INTERRUPTED
                    && record.interruptedState == CompetitionState.CLEANUP)) {
            buildController.cancelVisuals(record);
            record.cleanupTargetCount = record.buildCursor;
            record.buildCursor = 0;
        }
        record.state = CompetitionState.CLEANUP;
        record.stateTicks = 0;
        data.changed(record);
        context.getSource().sendSuccess(() -> Component.literal("正在还原比赛场地，完成后可重新召唤焰魔。"), false);
        return 1;
    }

    private int status(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        Optional<CompetitionRecord> optional = IgnisChallengeRegistry.get(source.getLevel()).byPlayer(player.getUUID());
        if (optional.isEmpty()) {
            source.sendFailure(Component.translatable("message.culinary_cataclysm.no_active"));
            return 0;
        }
        source.sendSuccess(() -> IgnisPresentation.statusText(optional.get()), false);
        return 1;
    }
}
