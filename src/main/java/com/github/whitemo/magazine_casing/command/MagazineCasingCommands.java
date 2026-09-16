package com.github.whitemo.magazine_casing.command;

import com.github.whitemo.magazine_casing.MagazineAndCasing;
import com.github.whitemo.magazine_casing.entity.CasingEntity;
import com.github.whitemo.magazine_casing.entity.MagazineEntity;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /magazinecasing clear casing|magazine|all}：清理所有维度中已掉落的弹壳 / 弹匣实体，权限等级 2。
 */
@Mod.EventBusSubscriber(modid = MagazineAndCasing.MOD_ID)
public class MagazineCasingCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("magazinecasing")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("clear")
                        .then(Commands.literal("casing")
                                .executes(ctx -> clear(ctx.getSource(), true, false, "commands.magazine_casing.clear.casing")))
                        .then(Commands.literal("magazine")
                                .executes(ctx -> clear(ctx.getSource(), false, true, "commands.magazine_casing.clear.magazine")))
                        .then(Commands.literal("all")
                                .executes(ctx -> clear(ctx.getSource(), true, true, "commands.magazine_casing.clear.all")))));
    }

    /**
     * 先收集再统一移除：实体表不支持边遍历边删除（会触发并发修改）。
     * 遍历所有维度，避免只清掉命令执行者所在维度的实体。
     */
    private static int clear(CommandSourceStack source, boolean casing, boolean magazine, String messageKey) {
        int removed = 0;
        for (ServerLevel level : source.getServer().getAllLevels()) {
            List<Entity> targets = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if ((casing && entity instanceof CasingEntity) || (magazine && entity instanceof MagazineEntity)) {
                    targets.add(entity);
                }
            }
            targets.forEach(Entity::discard);
            removed += targets.size();
        }

        String count = Integer.toString(removed);
        source.sendSuccess(() -> Component.translatable(messageKey, count), true);
        return removed;
    }
}
