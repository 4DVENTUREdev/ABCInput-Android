package net.HeZi.Android.HeInputLibrary;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;

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

    // Published only once fully built, so lookups never see half-filled data.
    private static volatile HashMap<String, String> sMap = null;
    private static volatile HashMap<String, Integer> sReadingCount = null; // "大"+"da" -> count
    private static volatile HashMap<Character, Integer> sCharTotal = null; // "大" -> total count
    private static volatile HashSet<String> sSyllables = null;             // all toneless syllables
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

    /** True if the string is a known toneless pinyin syllable (e.g. "da", "tai", "lv"). */
    public static boolean isSyllable(String s) {
        HashSet<String> set = sSyllables;
        return set != null && set.contains(s);
    }

    /**
     * Share (0.0 to 1.0) of this character's usage in multi-character words that
     * has this reading. Returns 1.0 when there is no data, so nothing gets demoted
     * by mistake (e.g. while the dictionary is still loading).
     */
    public static double readingShare(char c, String syllable) {
        HashMap<Character, Integer> totals = sCharTotal;
        HashMap<String, Integer> counts = sReadingCount;
        if (totals == null || counts == null) return 1.0;
        Integer total = totals.get(c);
        if (total == null || total == 0) return 1.0;
        Integer n = counts.get(c + syllable);
        return n == null ? 0.0 : n / (double) total;
    }
    /** All known toneless syllables that start with the typed prefix. */
    public static ArrayList<String> syllablesStartingWith(String prefix) {
        ArrayList<String> out = new ArrayList<String>();
        HashSet<String> set = sSyllables;
        if (set == null) return out;
        for (String s : set) if (s.startsWith(prefix)) out.add(s);
        return out;
    }

    /** Share of this character's usage covered by any of these syllables. 1.0 if no data. */
    public static double readingShare(char c, ArrayList<String> syllables) {
        HashMap<Character, Integer> totals = sCharTotal;
        HashMap<String, Integer> counts = sReadingCount;
        if (totals == null || counts == null) return 1.0;
        Integer total = totals.get(c);
        if (total == null || total == 0) return 1.0;
        int sum = 0;
        for (String s : syllables) {
            Integer n = counts.get(c + s);
            if (n != null) sum += n;
        }
        return sum / (double) total;
    }
    // ---------------------------------------------------------------- loading

    private static void load(Context context) {
        long start = System.currentTimeMillis();
        HashMap<String, String> map = new HashMap<>(250000);
        HashMap<String, Integer> counts = new HashMap<>();
        HashMap<Character, Integer> totals = new HashMap<>();
        HashSet<String> sylls = new HashSet<>();
        BufferedReader reader = null;
        try {
            InputStream in = context.getAssets().open(ASSET_NAME);
            reader = new BufferedReader(new InputStreamReader(in, "UTF-8"), 64 * 1024);
            String line;
            while ((line = reader.readLine()) != null) {
                parseLine(line, map, counts, totals, sylls);
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
            sReadingCount = counts;
            sCharTotal = totals;
            sSyllables = sylls;
            sMap = map;   // keep this last: isLoaded() keys off it
            Log.i(TAG, "Loaded " + map.size() + " keys in "
                    + (System.currentTimeMillis() - start) + " ms");
        }
    }

    private static void parseLine(String line, HashMap<String, String> map,
                                  HashMap<String, Integer> counts,
                                  HashMap<Character, Integer> totals,
                                  HashSet<String> sylls) {
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

        // ---- reading statistics (for demoting rare readings like 大 = tai) ----
        int b1 = line.indexOf('[', s2);
        int b2 = line.indexOf(']', s2);
        if (b1 >= 0 && b2 > b1) {
            String[] syl = line.substring(b1 + 1, b2).toLowerCase().split(" ");
            if (syl.length == simp.length()) {
                for (int i = 0; i < syl.length; i++) {
                    String s = syl[i].replaceAll("[0-9]", "").replace("u:", "v");
                    if (s.isEmpty() || !Character.isLetter(s.charAt(0))) continue;
                    sylls.add(s);
                    if (syl.length > 1) {   // multi-char words only: better usage signal
                        char c = simp.charAt(i);
                        String key = c + s;
                        Integer k = counts.get(key);
                        counts.put(key, k == null ? 1 : k + 1);
                        Integer t = totals.get(c);
                        totals.put(c, t == null ? 1 : t + 1);
                    }
                }
            }
        }

        // ---- gloss lookup ----
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