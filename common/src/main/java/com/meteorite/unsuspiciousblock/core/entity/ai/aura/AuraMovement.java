package com.meteorite.unsuspiciousblock.core.entity.ai.aura;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** AI 推进与盘旋目标的区域决策；原版继续负责转向计算与物理运动。 */
public final class AuraMovement {
    private static final double MAX_FLIGHT_DISTANCE = 16.0;
    private static final double COLLISION_CHUNK_MARGIN = 1.0;
    private static final Direction[] TARGET_DIRECTIONS = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    private static final Direction[] VELOCITY_DIRECTIONS = {
            Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.DOWN
    };

    private AuraMovement() {
    }

    /** 在 AI 写入处只撤回本次推进，携带速度仍由原版惯性、外力与重力处理。 */
    public static Vec3 selectGroundVelocity(Creeper creeper, Vec3 carriedVelocity, Vec3 proposedVelocity) {
        if (!AuraRepulsion.isRepelled(creeper) || creeper.isNoAi() || proposedVelocity.equals(carriedVelocity)) {
            return proposedVelocity;
        }
        if (AuraRepulsion.clearProtectedTarget(creeper)) {
            return carriedVelocity;
        }
        if (AuraRepulsion.blocksMovement(creeper, creeper.position().add(proposedVelocity))) {
            AuraRepulsion.stopMovement(creeper);
            return carriedVelocity;
        }
        return proposedVelocity;
    }

    /** 备用盘旋目标保留水平距离，使原版控制器能执行；无候选时原地等待重选。 */
    public static Vec3 selectFlightTarget(Phantom phantom, Vec3 requested) {
        if (!AuraRepulsion.isRepelled(phantom) || phantom.isNoAi()) {
            return requested;
        }
        AuraRepulsion.clearProtectedTarget(phantom);
        if (AuraRepulsion.canMoveTo(phantom, BlockPos.containing(requested))
                && !AuraRepulsion.blocksMovement(phantom, requested)) {
            return requested;
        }
        Vec3 desired = requested.subtract(phantom.position());
        Vec3 selected = phantom.position();
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Direction direction : TARGET_DIRECTIONS) {
            Vec3 step = Vec3.atLowerCornerOf(direction.getNormal()).scale(MAX_FLIGHT_DISTANCE);
            Vec3 target = phantom.position().add(step);
            double score = step.dot(desired);
            if (score > bestScore && AuraRepulsion.canMoveTo(phantom, BlockPos.containing(target))
                    && !AuraRepulsion.blocksMovement(phantom, target) && hasClearLanding(phantom, step)) {
                selected = target;
                bestScore = score;
            }
        }
        return selected;
    }

    /** 有限方向只替换本次转向贡献；所有候选受阻时保留完整携带速度。 */
    public static Vec3 selectFlightVelocity(Phantom phantom, Vec3 carriedVelocity, Vec3 proposedVelocity) {
        if (!AuraRepulsion.isRepelled(phantom) || phantom.isNoAi() || proposedVelocity.equals(carriedVelocity)) {
            return proposedVelocity;
        }
        if (AuraRepulsion.clearProtectedTarget(phantom)) {
            return carriedVelocity;
        }
        if (!AuraRepulsion.blocksMovement(phantom, phantom.position().add(proposedVelocity))) {
            return proposedVelocity;
        }
        Vec3 desired = proposedVelocity.subtract(carriedVelocity);
        double distance = desired.length();
        Vec3 selected = carriedVelocity;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Direction direction : VELOCITY_DIRECTIONS) {
            Vec3 contribution = Vec3.atLowerCornerOf(direction.getNormal()).scale(distance);
            Vec3 velocity = carriedVelocity.add(contribution);
            double score = contribution.dot(desired);
            if (score > bestScore && !AuraRepulsion.blocksMovement(phantom, phantom.position().add(velocity))
                    && hasClearLanding(phantom, velocity)) {
                selected = velocity;
                bestScore = score;
            }
        }
        return selected;
    }

    private static boolean hasClearLanding(Phantom phantom, Vec3 step) {
        AABB landing = phantom.getBoundingBox().move(step);
        AABB queried = landing.inflate(COLLISION_CHUNK_MARGIN);
        int minChunkX = SectionPos.blockToSectionCoord(Mth.floor(queried.minX));
        int maxChunkX = SectionPos.blockToSectionCoord(Mth.floor(queried.maxX));
        int minChunkZ = SectionPos.blockToSectionCoord(Mth.floor(queried.minZ));
        int maxChunkZ = SectionPos.blockToSectionCoord(Mth.floor(queried.maxZ));
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!phantom.level().getChunkSource().hasChunk(chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return phantom.level().noCollision(phantom, landing);
    }
}
