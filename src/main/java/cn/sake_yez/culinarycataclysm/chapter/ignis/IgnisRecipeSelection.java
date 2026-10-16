package cn.sake_yez.culinarycataclysm.chapter.ignis;

import cn.sake_yez.culinarycataclysm.CulinaryCataclysm;
import cn.sake_yez.culinarycataclysm.competition.ServingTable;
import cn.sake_yez.culinarycataclysm.integration.CookeryIntegration;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

public final class IgnisRecipeSelection {
    private static final ResourceLocation ROUND_CONFIG = ResourceLocation.fromNamespaceAndPath(
            CulinaryCataclysm.MOD_ID, "culinary_competition/rounds.json");

    private IgnisRecipeSelection() {
    }

    public static List<ResourceLocation> resolveConfiguredRecipes(MinecraftServer server) throws IOException {
        List<ResourceLocation> result = new ArrayList<>();
        try (Reader reader = server.getResourceManager().openAsReader(ROUND_CONFIG)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            for (var element : json.getAsJsonArray("rounds")) {
                ResourceLocation recipeId = ResourceLocation.tryParse(element.getAsString());
                if (recipeId == null) throw new IOException("无效菜谱 ID: " + element);
                CookeryIntegration.RecipeFacts facts = CookeryIntegration.recipeFacts(server, recipeId);
                if (!facts.supported()) throw new IOException("菜谱不是受支持的森罗炒锅或汤锅配方: " + recipeId);
                if (facts.result().isEmpty()) throw new IOException("菜谱没有可提交成品: " + recipeId);
                if (facts.ingredients().isEmpty()) throw new IOException("菜谱没有可验证食材: " + recipeId);
                if (!ServingTable.isPlaceableDish(facts.result())) {
                    throw new IOException("菜谱成品不是可单格立体摆盘的森罗食物: " + recipeId);
                }
                result.add(recipeId);
            }
        }
        if (result.isEmpty()) throw new IOException("比赛轮次配置为空");
        return List.copyOf(result);
    }
}
