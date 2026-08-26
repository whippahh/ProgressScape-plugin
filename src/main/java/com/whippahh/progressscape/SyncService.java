package com.whippahh.progressscape;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Varbits;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Singleton
public class SyncService
{
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String SUPABASE_URL = "https://hbfnvijfjboxhamjmlhm.supabase.co";
    private static final String SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImhiZm52aWpmamJveGhhbWptbGhtIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzM0NjcwNDYsImV4cCI6MjA4OTA0MzA0Nn0.wg9Ho_rZBXqH7ulFkT4p1pAamC5bpBDTRXI75_rCPAY";

    private static final int ACCOUNT_TYPE_VARPLAYER = 1777;

    // Same public endpoint RuneLite's own Hiscore panel queries. Gives full
    // lifetime KC for every ranked boss in one request — no session/chat
    // message limitation like the old bossKCs-only approach had.
    private static final String HISCORE_URL = "https://secure.runescape.com/m=hiscore_oldschool/index_lite.json";

    @Inject
    private OkHttpClient httpClient;

    @Inject
    private Gson gson;

    private final Map<String, Integer> bossKCs = new HashMap<>();

    // Populated by ProgressScapePlugin's clientscript-4100 listener as
    // collection log item slots get built (whether from natural browsing or
    // the search-all burst triggered by the Sync button). Never cleared, so
    // it only grows across the client session — repeated syncs accumulate.
    private final Set<String> collectionLogObtained = ConcurrentHashMap.newKeySet();

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

    public void recordCollectionLogItem(String rawName)
    {
        if (rawName == null || rawName.isEmpty()) return;
        String clean = rawName.replaceAll("<[^>]+>", "").trim();
        if (clean.isEmpty()) return;
        if (collectionLogObtained.add(clean))
        {
            log.debug("Collection log item captured: {}", clean);
        }
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

        JsonArray caCompleted = buildCombatAchievements(client);

        // Snapshot of this-session live chat captures. The full boss list is
        // actually built on the background thread in sendToSupabase(), merged
        // with a hiscores lookup — see the comment there for why.
        Map<String, Integer> bossKCSnapshot = new HashMap<>(bossKCs);

        JsonObject collectionLog = null;
        if (includeCollectionLog)
        {
            if (collectionLogObtained.isEmpty())
            {
                panel.setStatus("No collection log items captured — try again");
                return;
            }
            JsonArray obtained = new JsonArray();
            for (String name : collectionLogObtained)
            {
                obtained.add(name);
            }
            collectionLog = new JsonObject();
            collectionLog.add("All", obtained);
        }

        int accountTypeId = client.getVarpValue(ACCOUNT_TYPE_VARPLAYER);
        String accountType = accountTypeFromId(accountTypeId);

        JsonObject payload = new JsonObject();
        payload.addProperty("username", username);
        payload.addProperty("account_type", accountType);
        payload.add("quests", quests);
        payload.add("diaries", diaries);
        payload.add("ca_completed", caCompleted);
        // NOTE: "bosses" is intentionally not added here — sendToSupabase
        // adds it after merging a hiscores lookup (a network call, so it
        // belongs on the background thread) with bossKCSnapshot.

        final JsonObject finalCL = collectionLog;
        executor.submit(() -> sendToSupabase(payload, finalCL, bossKCSnapshot, panel));
    }

    private void sendToSupabase(JsonObject payload, JsonObject collectionLog,
                                Map<String, Integer> bossKCSnapshot, ProgressScapePanel panel)
    {
        try
        {
            String username = payload.get("username").getAsString();

            // Hiscores gives full lifetime KC for every ranked boss — the
            // same public endpoint RuneLite's own Hiscore panel uses. Live
            // in-session chat captures are merged on top since they can be
            // fresher than hiscores, which lags behind by several minutes.
            JsonObject bosses = fetchHiscoreBosses(username);
            for (Map.Entry<String, Integer> entry : bossKCSnapshot.entrySet())
            {
                bosses.addProperty(entry.getKey(), entry.getValue());
            }
            payload.add("bosses", bosses);

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
                panel.setStatus("Collection log synced! (" + collectionLogObtained.size() + " items)");
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

    /**
     * Queries the public OSRS hiscores (the same endpoint RuneLite's own
     * Hiscore panel uses) for every ranked activity's score. This includes
     * bosses and raids, but also clue scrolls, minigames, league points etc.
     * — we don't filter those out here; the website only acts on names it
     * recognizes as bosses, so any extra non-boss entries are harmless.
     */
    private JsonObject fetchHiscoreBosses(String username)
    {
        JsonObject bosses = new JsonObject();
        try
        {
            String url = HISCORE_URL + "?player=" + URLEncoder.encode(username, "UTF-8");
            Request request = new Request.Builder().url(url).build();
            try (Response response = httpClient.newCall(request).execute())
            {
                if (!response.isSuccessful() || response.body() == null)
                {
                    log.debug("Hiscore lookup failed for {}: {}", username, response.code());
                    return bosses;
                }
                JsonObject root = gson.fromJson(response.body().string(), JsonObject.class);
                JsonArray activities = (root != null) ? root.getAsJsonArray("activities") : null;
                if (activities == null) return bosses;

                for (JsonElement el : activities)
                {
                    JsonObject activity = el.getAsJsonObject();
                    String name = activity.get("name").getAsString();
                    int score = activity.get("score").getAsInt();
                    // score is -1 when unranked (never done, or below the
                    // hiscore cutoff) — only forward entries actually ranked.
                    if (score >= 0)
                    {
                        bosses.addProperty(name, score);
                    }
                }
            }
        }
        catch (Exception e)
        {
            log.warn("Hiscore boss lookup failed for {}", username, e);
        }
        return bosses;
    }

    private JsonArray buildCombatAchievements(Client client)
    {
        JsonArray completed = new JsonArray();
        for (CombatAchievement task : CombatAchievement.values())
        {
            if (task.isCompleted(client))
            {
                completed.add(task.getTaskName());
            }
        }
        return completed;
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
