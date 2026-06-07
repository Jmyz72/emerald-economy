package savage.commoneconomy.shop;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.WallSignBlock;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import savage.commoneconomy.EconomyManager;

public class ShopSignHelper {
    
    public static boolean placeSign(World world, BlockPos chestPos, Shop shop, net.minecraft.util.math.Direction playerFacing) {
        // Try player's facing direction first
        Direction[] prioritizedDirections;
        if (playerFacing != null && playerFacing.getAxis().isHorizontal()) {
            // Player is facing the chest, so we want the sign on the OPPOSITE side
            // (the side the player is standing on)
            Direction preferredSide = playerFacing.getOpposite();
            
            // Put preferred side first, then others
            prioritizedDirections = new Direction[4];
            prioritizedDirections[0] = preferredSide;
            int index = 1;
            for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                if (dir != preferredSide) {
                    prioritizedDirections[index++] = dir;
                }
            }
        } else {
            prioritizedDirections = new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        }
        
        for (Direction direction : prioritizedDirections) {
            BlockPos signPos = chestPos.offset(direction);
            BlockState signState = world.getBlockState(signPos);
            
            if (signState.isAir() || signState.isReplaceable()) {
                // Sign should face TOWARDS the chest, so we use the direction itself
                BlockState wallSign = getWallSignForDirection(direction);
                if (wallSign != null) {
                    world.setBlockState(signPos, wallSign);
                    updateSignText(world, signPos, shop);
                    return true;
                }
            }
        }
        
        return false;
    }
    
    private static BlockState getWallSignForDirection(Direction direction) {
        return switch (direction) {
            case NORTH -> Blocks.OAK_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING, Direction.NORTH);
            case SOUTH -> Blocks.OAK_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING, Direction.SOUTH);
            case EAST -> Blocks.OAK_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING, Direction.EAST);
            case WEST -> Blocks.OAK_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING, Direction.WEST);
            default -> null;
        };
    }
    
    public static void updateSignText(World world, BlockPos signPos, Shop shop) {
        if (world.getBlockEntity(signPos) instanceof SignBlockEntity signEntity) {
            String shopType = shop.isBuying() ? "Buying" : "Selling";
            String itemName = shop.getItem().getName().getString();
            if (itemName.length() > 15) {
                itemName = itemName.substring(0, 12) + "...";
            }
            
            String priceText = EconomyManager.getInstance().format(shop.getPrice());
            String stockText;
            
            if (world instanceof net.minecraft.server.world.ServerWorld serverWorld) {
                int stock = ShopStockCalculator.calculateStock(serverWorld, shop);
                if (stock == -1) {
                    stockText = "Stock: ∞";
                } else {
                    stockText = (shop.isBuying() ? "Space: " : "Stock: ") + stock;
                }
                // Update cached stock value in shop object
                if (stock != -1) {
                    shop.setStock(stock);
                }
            } else {
                // Fallback for client-side (shouldn't happen for logic, but maybe rendering)
                stockText = (shop.isBuying() ? "Space: " : "Stock: ") + shop.getStock();
            }
            
            Text headerText;
            if (shop.isAdmin()) {
                headerText = Text.literal("§4[Admin Shop]");
            } else {
                headerText = Text.literal("§1" + shop.getOwnerName());
            }
            
            SignText frontText = new SignText()
                    .withMessage(0, headerText)
                    .withMessage(1, Text.literal(itemName))
                    .withMessage(2, Text.literal(shopType + ": " + priceText))
                    .withMessage(3, Text.literal(stockText));
            
            signEntity.setText(frontText, true);
            signEntity.markDirty();
            world.updateListeners(signPos, world.getBlockState(signPos), world.getBlockState(signPos), 3);
        }
    }
    
    public static BlockPos findSignForChest(World world, BlockPos chestPos) {
        Direction[] directions = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        
        for (Direction direction : directions) {
            BlockPos signPos = chestPos.offset(direction);
            BlockState state = world.getBlockState(signPos);
            
            if (state.getBlock() instanceof WallSignBlock) {
                return signPos;
            }
        }
        
        return null;
    }
    
    public static void removeSign(World world, BlockPos signPos) {
        if (signPos != null) {
            world.setBlockState(signPos, Blocks.AIR.getDefaultState());
        }
    }
}
