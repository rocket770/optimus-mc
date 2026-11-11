package dev.optimus.learning;

import dev.optimus.core.ml.DgmlMovementModel;
import dev.optimus.core.ml.MovementModel;
import java.io.File;
import java.io.IOException;

/** Local, bounded DGML loading. No downloads or runtime Python dependency. */
final class ModelRepository {
    final MovementModel movement;
    final MovementModel expert;
    final String issue;

    ModelRepository(File directory) {
        MovementModel primary = null, secondary = null;
        String problem = "";
        File custom = new File(directory, "optimus-ml-model.dgml");
        if (custom.exists()) {
            try {
                primary = DgmlMovementModel.load(custom);
            } catch (IOException error) {
                problem = "invalid override; using bundled model";
            }
        }
        if (primary == null) {
            try {
                primary =
                        DgmlMovementModel.load(
                                ModelRepository.class.getResourceAsStream(
                                        "/assets/optimus/ml/movement.dgml"));
            } catch (IOException error) {
                problem = "model unavailable";
            }
        }
        File extra = new File(directory, "optimus-combat-model.dgml");
        if (extra.exists()) {
            try {
                secondary = DgmlMovementModel.load(extra);
            } catch (IOException error) {
                problem = "invalid optional expert";
            }
        }
        movement = primary;
        expert = secondary;
        issue = problem;
    }
}