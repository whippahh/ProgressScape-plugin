package com.whippahh.progressscape;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Varbits;
import net.runelite.api.widgets.Widget;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Singleton
public class SyncService
{
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String SUPABASE_URL = "https://hbfnvijfjboxhamjmlhm.supabase.co";
    private static final String SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImhiZm52aWpmamJveGhhbWptbGhtIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzM0NjcwNDYsImV4cCI6MjA4OTA0MzA0Nn0.wg9Ho_rZBXqH7ulFkT4p1pAamC5bpBDTRXI75_rCPAY";

    private static final int COLLECTION_LOG_GROUP_ID = 621;
    private static final int COLLECTION_LOG_ITEMS_CONTAINER = 36;
    private static final int ACCOUNT_TYPE_VARPLAYER = 1777;

    @Inject
    private OkHttpClient httpClient;

    @Inject
    private Gson gson;

    private final Map<String, Integer> bossKCs = new HashMap<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public void updateBossKC(String bossName, int kc)
    {
        bossKCs.put(bossName, kc);
        log.debug("KC updated: {} = {}", bossName, kc);
    }

    public void clearKCs()
    {
        bossKCs.clear();
    }

    public void sync(String username, boolean includeCollectionLog,
                     Client client, ProgressScapePanel panel)
    {
        JsonObject quests = new JsonObject();
        for (Quest quest : Quest.values())
        {
            QuestState state = quest.getState(client);
            quests.addProperty(quest.getName(), state.name());
        }

        JsonObject diaries = buildDiaries(client);

        JsonObject bosses = new JsonObject();
        for (Map.Entry<String, Integer> entry : bossKCs.entrySet())
        {
            bosses.addProperty(entry.getKey(), entry.getValue());
        }

        JsonObject collectionLog = null;
        if (includeCollectionLog)
        {
            collectionLog = buildCollectionLog(client);
            if (collectionLog == null)
            {
                panel.setStatus("Open your Collection Log first!");
                return;
            }
        }

        int accountTypeId = client.getVarpValue(ACCOUNT_TYPE_VARPLAYER);
        String accountType = accountTypeFromId(accountTypeId);

        JsonObject payload = new JsonObject();
        payload.addProperty("username", username);
        payload.addProperty("account_type", accountType);
        payload.add("quests", quests);
        payload.add("diaries", diaries);
        payload.add("bosses", bosses);

        final JsonObject finalCL = collectionLog;
        executor.submit(() -> sendToSupabase(payload, finalCL, panel));
    }

    private void sendToSupabase(JsonObject payload, JsonObject collectionLog,
                                ProgressScapePanel panel)
    {
        try
        {
            String username = payload.get("username").getAsString();

            Request playerRequest = new Request.Builder()
                    .url(SUPABASE_URL + "/rest/v1/players?on_conflict=username")
                    .header("apikey", SUPABASE_KEY)
                    .header("Authorization", "Bearer " + SUPABASE_KEY)
                    .header("Content-Type", "application/json")
                    .header("Prefer", "resolution=merge-duplicates")
                    .post(RequestBody.create(JSON, gson.toJson(payload)))
                    .build();

            try (Response response = httpClient.newCall(playerRequest).execute())
            {
                if (!response.isSuccessful())
                {
                    log.warn("ProgressScape player sync failed: {}", response.code());
                    panel.setStatus("Sync failed (" + response.code() + ")");
                    return;
                }
            }

            if (collectionLog != null)
            {
                JsonObject clPayload = new JsonObject();
                clPayload.addProperty("username", username);
                clPayload.add("log_data", collectionLog);

                Request clRequest = new Request.Builder()
                        .url(SUPABASE_URL + "/rest/v1/collection_log?on_conflict=username")
                        .header("apikey", SUPABASE_KEY)
                        .header("Authorization", "Bearer " + SUPABASE_KEY)
                        .header("Content-Type", "application/json")
                        .header("Prefer", "resolution=merge-duplicates")
                        .post(RequestBody.create(JSON, gson.toJson(clPayload)))
                        .build();

                try (Response response = httpClient.newCall(clRequest).execute())
                {
                    if (!response.isSuccessful())
                    {
                        log.warn("Collection log sync failed: {}", response.code());
                        panel.setStatus("Collection log failed (" + response.code() + ")");
                        return;
                    }
                }
                panel.setStatus("Collection log synced!");
            }
            else
            {
                panel.setStatus("Synced!");
            }

            log.debug("ProgressScape sync OK for {}", username);
        }
        catch (IOException e)
        {
            log.warn("ProgressScape sync error", e);
            panel.setStatus("Sync error — check connection");
        }
    }

    private JsonObject buildDiaries(Client client)
    {
        JsonObject diaries = new JsonObject();

        int[][] diaryVarbits = {
                { Varbits.DIARY_ARDOUGNE_EASY,   Varbits.DIARY_ARDOUGNE_MEDIUM,   Varbits.DIARY_ARDOUGNE_HARD,   Varbits.DIARY_ARDOUGNE_ELITE },
                { Varbits.DIARY_DESERT_EASY,     Varbits.DIARY_DESERT_MEDIUM,     Varbits.DIARY_DESERT_HARD,     Varbits.DIARY_DESERT_ELITE },
                { Varbits.DIARY_FALADOR_EASY,    Varbits.DIARY_FALADOR_MEDIUM,    Varbits.DIARY_FALADOR_HARD,    Varbits.DIARY_FALADOR_ELITE },
                { Varbits.DIARY_FREMENNIK_EASY,  Varbits.DIARY_FREMENNIK_MEDIUM,  Varbits.DIARY_FREMENNIK_HARD,  Varbits.DIARY_FREMENNIK_ELITE },
                { Varbits.DIARY_KANDARIN_EASY,   Varbits.DIARY_KANDARIN_MEDIUM,   Varbits.DIARY_KANDARIN_HARD,   Varbits.DIARY_KANDARIN_ELITE },
                { Varbits.DIARY_KARAMJA_EASY,    Varbits.DIARY_KARAMJA_MEDIUM,    Varbits.DIARY_KARAMJA_HARD,    Varbits.DIARY_KARAMJA_ELITE },
                { Varbits.DIARY_KOUREND_EASY,    Varbits.DIARY_KOUREND_MEDIUM,    Varbits.DIARY_KOUREND_HARD,    Varbits.DIARY_KOUREND_ELITE },
                { Varbits.DIARY_LUMBRIDGE_EASY,  Varbits.DIARY_LUMBRIDGE_MEDIUM,  Varbits.DIARY_LUMBRIDGE_HARD,  Varbits.DIARY_LUMBRIDGE_ELITE },
                { Varbits.DIARY_MORYTANIA_EASY,  Varbits.DIARY_MORYTANIA_MEDIUM,  Varbits.DIARY_MORYTANIA_HARD,  Varbits.DIARY_MORYTANIA_ELITE },
                { Varbits.DIARY_VARROCK_EASY,    Varbits.DIARY_VARROCK_MEDIUM,    Varbits.DIARY_VARROCK_HARD,    Varbits.DIARY_VARROCK_ELITE },
                { Varbits.DIARY_WESTERN_EASY,    Varbits.DIARY_WESTERN_MEDIUM,    Varbits.DIARY_WESTERN_HARD,    Varbits.DIARY_WESTERN_ELITE },
                { Varbits.DIARY_WILDERNESS_EASY, Varbits.DIARY_WILDERNESS_MEDIUM, Varbits.DIARY_WILDERNESS_HARD, Varbits.DIARY_WILDERNESS_ELITE },
        };

        String[] diaryNames = {
                "Ardougne", "Desert", "Falador", "Fremennik", "Kandarin",
                "Karamja", "Kourend & Kebos", "Lumbridge & Draynor",
                "Morytania", "Varrock", "Western Provinces", "Wilderness"
        };

        for (int i = 0; i < diaryNames.length; i++)
        {
            JsonObject tiers = new JsonObject();
            tiers.addProperty("easy",   client.getVarbitValue(diaryVarbits[i][0]) == 1);
            tiers.addProperty("medium", client.getVarbitValue(diaryVarbits[i][1]) == 1);
            tiers.addProperty("hard",   client.getVarbitValue(diaryVarbits[i][2]) == 1);
            tiers.addProperty("elite",  client.getVarbitValue(diaryVarbits[i][3]) == 1);
            diaries.add(diaryNames[i], tiers);
        }

        return diaries;
    }

    private JsonObject buildCollectionLog(Client client)
    {
        Widget logContainer = client.getWidget(COLLECTION_LOG_GROUP_ID, COLLECTION_LOG_ITEMS_CONTAINER);
        if (logContainer == null || logContainer.isHidden())
        {
            return null;
        }

        JsonObject log = new JsonObject();
        Widget[] items = logContainer.getDynamicChildren();
        if (items == null) return log;

        Widget header = client.getWidget(COLLECTION_LOG_GROUP_ID, 19);
        String category = (header != null) ? header.getText() : "Unknown";

        com.google.gson.JsonArray obtained = new com.google.gson.JsonArray();
        for (Widget item : items)
        {
            if (item.getOpacity() == 0 && item.getName() != null && !item.getName().isEmpty())
            {
                obtained.add(item.getName().replaceAll("<[^>]+>", "").trim());
            }
        }

        log.add(category, obtained);
        return log;
    }

    private String accountTypeFromId(int id)
    {
        switch (id)
        {
            case 1:  return "IRONMAN";
            case 2:  return "HARDCORE_IRONMAN";
            case 3:  return "ULTIMATE_IRONMAN";
            case 4:  return "GROUP_IRONMAN";
            default: return "NORMAL";
        }
    }
}
