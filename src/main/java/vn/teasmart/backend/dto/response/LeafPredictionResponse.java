package vn.teasmart.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import vn.teasmart.backend.exception.LeafAnalysisException;

public record LeafPredictionResponse(
        @JsonProperty("media_type") String mediaType,
        @JsonProperty("model_id") String modelId,
        @JsonProperty("class_index") int classIndex,
        String label,
        @JsonProperty("prediction_score") double predictionScore,
        @JsonProperty("class_probabilities") List<ClassProbability> classProbabilities,
        @JsonProperty("video") VideoAgreement video,
        List<String> warnings,
        String notice) {

    private static final int CLASSES = 7;
    private static final int MAX_FRAMES = 8;
    private static final double EPSILON = 0.00001;

    public record ClassProbability(
            @JsonProperty("class_index") int classIndex, String label, double probability) {
    }

    public record FramePrediction(
            @JsonProperty("timestamp_seconds") double timestampSeconds,
            @JsonProperty("class_index") int classIndex,
            String label,
            @JsonProperty("prediction_score") double predictionScore) {
    }

    public record VideoAgreement(
            @JsonProperty("duration_seconds") double durationSeconds,
            @JsonProperty("sampled_frames") int sampledFrames,
            @JsonProperty("agreeing_frames") int agreeingFrames,
            @JsonProperty("agreement_ratio") double agreementRatio,
            List<FramePrediction> frames) {
    }

    public static LeafPredictionResponse parse(JsonNode node, boolean videoRequest) {
        String media = text(node.path("media_type"), 5);
        require(media.equals(videoRequest ? "video" : "image"));
        String model = text(node.path("model_id"), 100);
        int winner = integer(node.path("class_index"), 0, CLASSES - 1);
        String winnerLabel = text(node.path("label"), 120);
        double score = number(node.path("prediction_score"), 1);
        List<ClassProbability> classes = classes(node.path("class_probabilities"));
        require(winnerLabel.equals(classes.get(winner).label())
                && Math.abs(score - classes.get(winner).probability()) <= EPSILON
                && classes.stream().noneMatch(value -> value.probability() > score + EPSILON));

        JsonNode videoNode = node.path("video");
        require(videoRequest ? videoNode.isObject() : videoNode.isNull());
        VideoAgreement agreement = videoRequest ? agreement(videoNode, classes, winner) : null;
        JsonNode items = node.path("warnings");
        require(items.isArray() && items.size() <= 8);
        List<String> warnings = new ArrayList<>();
        for (JsonNode item : items) {
            String warning = text(item, 70);
            require(warning.matches("[A-Z][A-Z0-9_]*"));
            warnings.add(warning);
        }
        return new LeafPredictionResponse(media, model, winner, winnerLabel, score, classes,
                agreement, List.copyOf(warnings), text(node.path("notice"), 300));
    }

    private static List<ClassProbability> classes(JsonNode entries) {
        require(entries.isArray() && entries.size() == CLASSES);
        List<ClassProbability> values = new ArrayList<>();
        double sum = 0;
        for (int index = 0; index < CLASSES; index++) {
            JsonNode entry = entries.path(index);
            require(integer(entry.path("class_index"), 0, CLASSES - 1) == index);
            double probability = number(entry.path("probability"), 1);
            values.add(new ClassProbability(index, text(entry.path("label"), 120), probability));
            sum += probability;
        }
        require(Math.abs(sum - 1) <= EPSILON);
        return List.copyOf(values);
    }

    private static VideoAgreement agreement(JsonNode node, List<ClassProbability> classes, int winner) {
        double duration = number(node.path("duration_seconds"), 15);
        require(duration > 0);
        int sampled = integer(node.path("sampled_frames"), 1, MAX_FRAMES);
        int agreeing = integer(node.path("agreeing_frames"), 0, sampled);
        double ratio = number(node.path("agreement_ratio"), 1);
        require(Math.abs(ratio - (double) agreeing / sampled) <= EPSILON);
        JsonNode frames = node.path("frames");
        require(frames.isArray() && frames.size() == sampled);
        List<FramePrediction> results = new ArrayList<>();
        double previous = -1;
        int winners = 0;
        for (JsonNode frame : frames) {
            double time = number(frame.path("timestamp_seconds"), duration + EPSILON);
            int index = integer(frame.path("class_index"), 0, CLASSES - 1);
            String label = text(frame.path("label"), 120);
            require(time >= previous && label.equals(classes.get(index).label()));
            results.add(new FramePrediction(time, index, label, number(frame.path("prediction_score"), 1)));
            previous = time;
            if (index == winner) {
                winners++;
            }
        }
        require(winners == agreeing);
        return new VideoAgreement(duration, sampled, agreeing, ratio, List.copyOf(results));
    }

    private static String text(JsonNode node, int maxLength) {
        require(node.isString());
        String value = node.stringValue();
        require(!value.isBlank() && value.length() <= maxLength);
        return value;
    }

    private static int integer(JsonNode node, int min, int max) {
        require(node.isIntegralNumber() && node.canConvertToInt());
        int value = node.intValue();
        require(value >= min && value <= max);
        return value;
    }

    private static double number(JsonNode node, double max) {
        require(node.isNumber());
        double value = node.doubleValue();
        require(Double.isFinite(value) && value >= 0 && value <= max);
        return value;
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw LeafAnalysisException.invalidResponse();
        }
    }
}
