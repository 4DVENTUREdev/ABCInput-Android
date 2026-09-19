// TODO: change this to match the package line at the top of HeInput_DataServer.java
package net.HeZi.Android.HeInputLibrary;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;

/**
 * In-memory CC-CEDICT lookup.
 *
 * Usage:
 *   CedictDictionary.init(context);          // once, e.g. in HeInputService.onCreate()
 *   String gloss = CedictDictionary.lookup("飞机");   // "" if not loaded yet or no match
 *
 * Loads assets/cedict.txt on a background thread so the keyboard never blocks on parsing.
 * Until loading finishes, lookup() simply returns "".
 *
 * CC-CEDICT line format:
 *   Traditional Simplified [pin1 yin1] /gloss 1/gloss 2/.../
 */
public final class CedictDictionary {

    private static final String TAG = "CedictDictionary";
    private static final String ASSET_NAME = "cedict.txt";

    /** Max glosses joined together for one dictionary entry, e.g. "airplane; plane". */
    private static final int MAX_GLOSSES_PER_ENTRY = 2;
    /** Stop adding a second gloss if the combined text would exceed this. */
    private static final int SOFT_GLOSS_LENGTH = 40;
    /** Hard cap on stored gloss length (after merging multiple readings). */
    private static final int MAX_GLOSS_LENGTH = 60;

    // Published only once fully built, so lookup() never sees a half-filled map.
    private static volatile HashMap<String, String> sMap = null;
    private static boolean sLoading = false;

    private CedictDictionary() {}

    /** Safe to call repeatedly; only the first call does any work. */
    public static synchronized void init(Context context) {
        if (sMap != null || sLoading) {
            return;
        }
        sLoading = true;
        final Context appContext = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                load(appContext);
            }
        }, "cedict-loader").start();
    }

    public static boolean isLoaded() {
        return sMap != null;
    }

    /** Returns a short English gloss for the word, or "" if unavailable. Never null. */
    public static String lookup(String ziCi) {
        HashMap<String, String> map = sMap;
        if (map == null || ziCi == null) {
            return "";
        }
        String gloss = map.get(ziCi);
        return gloss == null ? "" : gloss;
    }

    // ---------------------------------------------------------------- loading

    private static void load(Context context) {
        long start = System.currentTimeMillis();
        HashMap<String, String> map = new HashMap<>(250000);
        BufferedReader reader = null;
        try {
            InputStream in = context.getAssets().open(ASSET_NAME);
            reader = new BufferedReader(new InputStreamReader(in, "UTF-8"), 64 * 1024);
            String line;
            while ((line = reader.readLine()) != null) {
                parseLine(line, map);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to load " + ASSET_NAME, e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
            synchronized (CedictDictionary.class) {
                sLoading = false;
            }
        }

        if (!map.isEmpty()) {
            sMap = map;
            Log.i(TAG, "Loaded " + map.size() + " keys in "
                    + (System.currentTimeMillis() - start) + " ms");
        }
    }

    private static void parseLine(String line, HashMap<String, String> map) {
        if (line.isEmpty() || line.charAt(0) == '#') {
            return;
        }
        int s1 = line.indexOf(' ');
        if (s1 <= 0) return;
        int s2 = line.indexOf(' ', s1 + 1);
        if (s2 < 0) return;
        int firstSlash = line.indexOf('/', s2);
        int lastSlash = line.lastIndexOf('/');
        if (firstSlash < 0 || lastSlash <= firstSlash) return;

        String trad = line.substring(0, s1);
        String simp = line.substring(s1 + 1, s2);
        String gloss = pickGloss(line.substring(firstSlash + 1, lastSlash));
        if (gloss == null) return;

        addEntry(map, simp, gloss);
        if (!trad.equals(simp)) {
            addEntry(map, trad, gloss);
        }
    }

    /** Some words appear in several entries (different readings); keep at most two. */
    private static void addEntry(HashMap<String, String> map, String key, String gloss) {
        String existing = map.get(key);
        if (existing == null) {
            map.put(key, gloss);
        } else if (existing.indexOf(" | ") < 0
                && !existing.equals(gloss)
                && existing.length() + gloss.length() + 3 <= MAX_GLOSS_LENGTH) {
            map.put(key, existing + " | " + gloss);
        }
    }

    /** Builds a short display gloss from the "/"-separated definitions. */
    private static String pickGloss(String defs) {
        String[] parts = defs.split("/");
        StringBuilder sb = new StringBuilder();
        int count = 0;
        String firstRaw = null;

        for (String part : parts) {
            String g = part.trim();
            if (g.isEmpty()) continue;
            if (firstRaw == null) firstRaw = g;
            if (isNoise(g)) continue;

            if (count > 0) {
                if (sb.length() + g.length() + 2 > SOFT_GLOSS_LENGTH) break;
                sb.append("; ");
            }
            sb.append(g);
            count++;
            if (count >= MAX_GLOSSES_PER_ENTRY) break;
        }

        String result;
        if (count > 0) {
            result = sb.toString();
        } else if (firstRaw != null) {
            result = firstRaw;   // only "see X" / "variant of X" style glosses exist
        } else {
            return null;
        }

        if (result.length() > MAX_GLOSS_LENGTH) {
            result = result.substring(0, MAX_GLOSS_LENGTH - 1) + "\u2026";
        }
        return result;
    }

    /** Glosses that are cross-references or measure words rather than meanings. */
    private static boolean isNoise(String g) {
        return g.startsWith("CL:")
                || g.startsWith("see ")
                || g.startsWith("see also ")
                || g.startsWith("variant of ")
                || g.startsWith("old variant of ")
                || g.startsWith("erhua variant of ")
                || g.startsWith("also pr. ");
    }
}
