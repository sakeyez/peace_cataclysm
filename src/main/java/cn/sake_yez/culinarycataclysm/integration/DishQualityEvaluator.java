package cn.sake_yez.culinarycataclysm.integration;

import cn.sake_yez.culinarycataclysm.competition.CookedDishEvidence;

import java.util.ArrayList;
import java.util.List;

/** 只消费可验证生产事实；玩家和 NPC 走完全相同的函数。 */
public final class DishQualityEvaluator {
    private DishQualityEvaluator() {
    }

    public static Evaluation evaluate(CookedDishEvidence evidence) {
        int recipe = evidence.realCookeryStation && evidence.heated ? 40 : 0;
        int ingredients = evidence.consumedIngredients.isEmpty() ? 0 : 20;
        int process = evidence.requiredOperations <= 0 ? 0
                : Math.min(20, (evidence.verifiedOperations * 20) / evidence.requiredOperations);
        int quality = qualityPoints(evidence.cookeryQuality);
        int total = recipe + ingredients + process + quality;
        List<String> reasons = new ArrayList<>();
        reasons.add("真实锅具与热源 " + recipe + "/40");
        reasons.add("可追溯食材 " + ingredients + "/20");
        reasons.add("有效工序 " + process + "/20");
        reasons.add("森罗品质 " + quality + "/20 (" + evidence.cookeryQuality + ")");
        return new Evaluation(total, List.copyOf(reasons));
    }

    private static int qualityPoints(String quality) {
        return switch (quality) {
            case "superb" -> 20;
            case "excellent" -> 16;
            case "standard" -> 12;
            case "poor" -> 6;
            default -> 0;
        };
    }

    public record Evaluation(int score, List<String> reasons) {
    }
}
