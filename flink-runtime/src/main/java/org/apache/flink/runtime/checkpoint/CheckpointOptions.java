/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.checkpoint;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.runtime.jobgraph.tasks.AbstractInvokable;
import org.apache.flink.runtime.state.CheckpointStorageLocationReference;

import java.io.Serializable;
import java.util.Objects;

import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * Options for performing the checkpoint.
 *
 * <p>The {@link CheckpointProperties} are related and cover properties that are only relevant at
 * the {@link CheckpointCoordinator}. These options are relevant at the {@link AbstractInvokable}
 * instances running on task managers.
 */
public class CheckpointOptions implements Serializable {

    /** How a checkpoint should be aligned. */
    public enum AlignmentType {
        AT_LEAST_ONCE,
        ALIGNED,
        UNALIGNED,
        FORCED_ALIGNED
    }

    public static final long NO_ALIGNMENT_TIME_OUT = Long.MAX_VALUE;

    private static final long serialVersionUID = 5010126558083292915L;

    /** Type of the checkpoint. */
    private final CheckpointType checkpointType;

    /** Target location for the checkpoint. */
    private final CheckpointStorageLocationReference targetLocation;

    private final AlignmentType alignmentType;

    public static CheckpointOptions notExactlyOnce(
            CheckpointType type, CheckpointStorageLocationReference location) {
        return new CheckpointOptions(type, location, AlignmentType.AT_LEAST_ONCE);
    }

    public static CheckpointOptions aligned(
            CheckpointType type, CheckpointStorageLocationReference location) {
        return new CheckpointOptions(type, location, AlignmentType.ALIGNED);
    }

    public static CheckpointOptions unaligned(CheckpointStorageLocationReference location) {
        return new CheckpointOptions(CheckpointType.CHECKPOINT, location, AlignmentType.UNALIGNED);
    }

    private static CheckpointOptions forceAligned(CheckpointStorageLocationReference location) {
        return new CheckpointOptions(
                CheckpointType.CHECKPOINT, location, AlignmentType.FORCED_ALIGNED);
    }

    public static CheckpointOptions forConfig(
            CheckpointType checkpointType,
            CheckpointStorageLocationReference locationReference,
            boolean isExactlyOnceMode,
            boolean isUnalignedEnabled) {
        if (!isExactlyOnceMode) {
            return notExactlyOnce(checkpointType, locationReference);
        } else if (checkpointType.isSavepoint()) {
            return aligned(checkpointType, locationReference);
        } else if (!isUnalignedEnabled) {
            return aligned(checkpointType, locationReference);
        } else {
            return unaligned(locationReference);
        }
    }

    @VisibleForTesting
    public CheckpointOptions(
            CheckpointType checkpointType, CheckpointStorageLocationReference targetLocation) {
        this(checkpointType, targetLocation, AlignmentType.ALIGNED);
    }

    public CheckpointOptions(
            CheckpointType checkpointType,
            CheckpointStorageLocationReference targetLocation,
            AlignmentType alignmentType) {

        checkArgument(
                alignmentType != AlignmentType.UNALIGNED || !checkpointType.isSavepoint(),
                "Savepoint can't be unaligned");
        this.checkpointType = checkNotNull(checkpointType);
        this.targetLocation = checkNotNull(targetLocation);
        this.alignmentType = checkNotNull(alignmentType);
    }

    public boolean needsAlignment() {
        return isExactlyOnceMode()
                && (getCheckpointType().isSavepoint() || !isUnalignedCheckpoint());
    }

    public AlignmentType getAlignment() {
        return alignmentType;
    }

    // ------------------------------------------------------------------------

    /** Returns the type of checkpoint to perform. */
    public CheckpointType getCheckpointType() {
        return checkpointType;
    }

    /** Returns the target location for the checkpoint. */
    public CheckpointStorageLocationReference getTargetLocation() {
        return targetLocation;
    }

    public boolean isExactlyOnceMode() {
        return alignmentType != AlignmentType.AT_LEAST_ONCE;
    }

    public boolean isUnalignedCheckpoint() {
        return alignmentType == AlignmentType.UNALIGNED;
    }

    public CheckpointOptions withUnalignedSupported() {
        if (alignmentType == AlignmentType.FORCED_ALIGNED) {
            return unaligned(targetLocation);
        }
        return this;
    }

    public CheckpointOptions withUnalignedUnsupported() {
        if (isUnalignedCheckpoint()) {
            return forceAligned(targetLocation);
        }
        return this;
    }

    // ------------------------------------------------------------------------

    @Override
    public int hashCode() {
        return Objects.hash(targetLocation, checkpointType, alignmentType);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        } else if (obj != null && obj.getClass() == CheckpointOptions.class) {
            final CheckpointOptions that = (CheckpointOptions) obj;
            return this.checkpointType == that.checkpointType
                    && this.targetLocation.equals(that.targetLocation)
                    && this.alignmentType == that.alignmentType;
        } else {
            return false;
        }
    }

    @Override
    public String toString() {
        return "CheckpointOptions {"
                + "checkpointType = "
                + checkpointType
                + ", targetLocation = "
                + targetLocation
                + ", alignment = "
                + alignmentType
                + "}";
    }

    // ------------------------------------------------------------------------
    //  Factory methods
    // ------------------------------------------------------------------------

    private static final CheckpointOptions CHECKPOINT_AT_DEFAULT_LOCATION =
            new CheckpointOptions(
                    CheckpointType.CHECKPOINT, CheckpointStorageLocationReference.getDefault());

    @VisibleForTesting
    public static CheckpointOptions forCheckpointWithDefaultLocation() {
        return CHECKPOINT_AT_DEFAULT_LOCATION;
    }

    public CheckpointOptions toUnaligned() {
        checkState(alignmentType == AlignmentType.ALIGNED);
        return unaligned(targetLocation);
    }
}
