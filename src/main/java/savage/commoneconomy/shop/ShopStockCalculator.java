package savage.commoneconomy.shop;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public class ShopStockCalculator {

    public static int calculateStock(ServerWorld world, Shop shop) {
        if (shop.isAdmin()) {
            return -1; // Infinite stock
        }

        BlockEntity blockEntity = world.getBlockEntity(shop.getChestLocation());
        if (!(blockEntity instanceof ChestBlockEntity chest)) {
            return 0;
        }

        if (shop.isBuying()) {
            // Shop is buying -> Calculate how much space is available for the item
            return calculateSpaceForItems(chest, shop.getItem());
        } else {
            // Shop is selling -> Calculate how many items are in the chest
            return countItemsInInventory(chest, shop.getItem());
        }
    }

    private static int countItemsInInventory(ChestBlockEntity chest, ItemStack template) {
        int count = 0;
        for (int i = 0; i < chest.size(); i++) {
            ItemStack stack = chest.getStack(i);
            if (ItemStack.areItemsAndComponentsEqual(stack, template)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int calculateSpaceForItems(ChestBlockEntity chest, ItemStack template) {
        int space = 0;
        int maxStackSize = template.getMaxCount();

        for (int i = 0; i < chest.size(); i++) {
            ItemStack stack = chest.getStack(i);
            if (stack.isEmpty()) {
                space += maxStackSize;
            } else if (ItemStack.areItemsAndComponentsEqual(stack, template)) {
                space += (maxStackSize - stack.getCount());
            }
        }
        return space;
    }
}
