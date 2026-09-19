package net.HeZi.Android.HeInputLibrary.InputEngine;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

/**
 * Word-level pinyin matching plus unified frequency ranking of characters and words.
 *
 * Words are matched by full pinyin ("feiji"), initials ("fj"), mixed ("feij"), and completion
 * ("fei" -> 飞机). Every candidate (single characters from the old engine and multi-character
 * words from here) gets a score = jieba frequency x a weight for how it matched, and the whole
 * list is sorted by that score.
 *
 * Data (both in assets/):
 *   cedict.txt      - supplies each word's pinyin
 *   jieba_dict.txt  - jieba's dict.txt renamed; supplies word AND single-character frequencies.
 *                     If missing, words get equal weight and characters keep their old order.
 *
 * Usage:
 *   WordEngine.init(context);                       // once, e.g. HeInputService.onCreate()
 *   ArrayList<String> ranked = WordEngine.rank(typed, oldEngineChars, wordsAllowed);
 *
 * Matching rule for words: the typed string is split across the word's syllables, each syllable
 * getting a non-empty prefix of itself. "fj" = f(ei) j(i), "feij" = fei j(i), "feiji" = fei ji.
 */
public final class WordEngine {

    private static final String TAG = "WordEngine";
    private static final String CEDICT_ASSET = "cedict.txt";
    private static final String FREQ_ASSET = "jieba_dict.txt";

    /** Set true to log the top of each ranked list (text + score) while tuning the weights. */
    private static final boolean DEBUG = false;

    /** Drop words rarer than this (jieba frequency). */
    private static final int MIN_FREQ = 5;
    /** Word matching only starts once this many letters are typed. */
    private static final int MIN_QUERY_LENGTH = 2;
    /** Max words taken per match type (they are taken in frequency order). */
    private static final int MAX_PER_TIER = 50;

    // ---- ranking weights: score = frequency x weight. Tune these by feel. ----
    /** Single characters coming from the old engine. */
    private static final double W_CHAR = 1.0;
    /** Word whose full pinyin equals the typed text ("feiji" -> 飞机). */
    private static final double W_EXACT = 1.0;
    /** Word fully covered by initials / mixed input ("fj", "feij" -> 飞机). */
    private static final double W_COVER = 0.5;
    /** Longer word completed from a typed prefix ("fei" -> 飞机). */
    private static final double W_COMPLETE = 0.25;

    private static final class Entry {
        final String word;      // e.g. 飞机
        final String joined;    // pinyin without tones/spaces, e.g. feiji
        final byte[] sylLen;    // syllable lengths, e.g. {3, 2}
        int freq;

        Entry(String word, String joined, byte[] sylLen) {
            this.word = word;
            this.joined = joined;
            this.sylLen = sylLen;
        }
    }

    private static final class Scored {
        final String text;
        final double score;

        Scored(String text, double score) {
            this.text = text;
            this.score = score;
        }
    }

    private static final class Freqs {
        final HashMap<String, Integer> words = new HashMap<String, Integer>();
        final HashMap<Character, Integer> chars = new HashMap<Character, Integer>();
        boolean have = false;
    }

    private static final Comparator<Scored> BY_SCORE_DESC = new Comparator<Scored>() {
        @Override
        public int compare(Scored a, Scored b) {
            return Double.compare(b.score, a.score);   // Collections.sort is stable: ties keep order
        }
    };

    // sBuckets[c - 'a'] = entries whose pinyin starts with letter c, sorted by frequency (desc).
    private static volatile Entry[][] sBuckets = null;
    private static volatile HashMap<Character, Integer> sCharFreq = null;
    private static boolean sLoading = false;

    private WordEngine() {}

    public static synchronized void init(Context context) {
        if (sBuckets != null || sLoading) {
            return;
        }
        sLoading = true;
        final Context appContext = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                load(appContext);
            }
        }, "wordengine-loader").start();
    }

    public static boolean isLoaded() {
        return sBuckets != null;
    }

    /**
     * Merges the old engine's single characters with matching words and sorts everything by
     * frequency score. Duplicates are removed.
     *
     * @param typed        lowercase letters a-z typed so far
     * @param chars        candidates from the old engine, in its original order
     * @param includeWords false while a numpad letter is half-entered (then only chars are ranked)
     * @return ranked candidates; if the engine isn't loaded yet, {@code chars} unchanged
     */
    public static ArrayList<String> rank(String typed, ArrayList<String> chars, boolean includeWords) {
        Entry[][] buckets = sBuckets;
        HashMap<Character, Integer> charFreq = sCharFreq;
        if (buckets == null || charFreq == null || typed == null || typed.isEmpty()) {
            return chars;
        }

        ArrayList<Scored> all = new ArrayList<Scored>(chars.size() + 3 * MAX_PER_TIER);
        HashSet<String> seen = new HashSet<String>();

        for (String c : chars) {
            if (!seen.add(c)) continue;   // heteronyms show up once per reading in the old list
            Integer f = (c.length() == 1) ? charFreq.get(c.charAt(0)) : null;
            all.add(new Scored(c, (f == null ? 0 : f) * W_CHAR));
        }

        if (includeWords && typed.length() >= MIN_QUERY_LENGTH) {
            collectWords(typed, buckets, all, seen);
        }

        Collections.sort(all, BY_SCORE_DESC);

        ArrayList<String> out = new ArrayList<String>(all.size());
        for (int i = 0; i < all.size(); i++) {
            Scored s = all.get(i);
            out.add(s.text);
            if (DEBUG && i < 10) {
                Log.d(TAG, typed + " #" + i + " " + s.text + " score=" + s.score);
            }
        }
        return out;
    }

    private static void collectWords(String typed, Entry[][] buckets,
                                     ArrayList<Scored> out, HashSet<String> seen) {
        for (int i = 0; i < typed.length(); i++) {
            char c = typed.charAt(i);
            if (c < 'a' || c > 'z') return;
        }
        Entry[] bucket = buckets[typed.charAt(0) - 'a'];
        if (bucket == null) return;

        int[] counts = new int[3];
        double[] weights = {W_EXACT, W_COVER, W_COMPLETE};

        for (Entry e : bucket) {
            int tier;
            if (e.joined.equals(typed)) {
                tier = 0;
            } else if (e.sylLen.length <= typed.length() && dfs(typed, 0, e, 0, 0, true)) {
                tier = 1;
            } else if (dfs(typed, 0, e, 0, 0, false)) {
                tier = 2;
            } else {
                continue;
            }

            if (counts[tier] < MAX_PER_TIER && seen.add(e.word)) {
                out.add(new Scored(e.word, e.freq * weights[tier]));
                counts[tier]++;
            }
            if (counts[0] >= MAX_PER_TIER && counts[1] >= MAX_PER_TIER
                    && counts[2] >= MAX_PER_TIER) {
                break;
            }
        }
    }

    /**
     * Can the typed string, from position pos, be split into non-empty prefixes of the word's
     * syllables starting at syllable k?
     * requireAll = true : must end exactly after the last syllable (all syllables covered)
     * requireAll = false: may end earlier (completion of a longer word)
     */
    private static boolean dfs(String t, int pos, Entry e, int k, int off, boolean requireAll) {
        int n = e.sylLen.length;
        if (pos == t.length()) {
            return requireAll ? (k == n) : (k >= 1);
        }
        if (k == n) {
            return false;
        }
        int sl = e.sylLen[k];
        int max = Math.min(sl, t.length() - pos);
        for (int l = max; l >= 1; l--) {
            if (t.regionMatches(pos, e.joined, off, l)
                    && dfs(t, pos + l, e, k + 1, off + sl, requireAll)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- loading

    private static void load(Context context) {
        long start = System.currentTimeMillis();
        Entry[][] buckets = null;
        HashMap<Character, Integer> charFreq = null;
        try {
            ArrayList<Entry> cands = readCedict(context);
            Freqs freqs = readFrequencies(context, cands);
            ArrayList<Entry> kept = filterByFrequency(cands, freqs);
            buckets = buildBuckets(kept);
            charFreq = freqs.chars;
            Log.i(TAG, "Indexed " + kept.size() + " words and " + freqs.chars.size()
                    + " characters with frequencies in "
                    + (System.currentTimeMillis() - start) + " ms");
        } catch (IOException e) {
            Log.e(TAG, "Failed to build word index", e);
        } finally {
            synchronized (WordEngine.class) {
                sLoading = false;
            }
        }
        if (buckets != null) {
            sCharFreq = charFreq;   // publish before sBuckets, which rank() checks
            sBuckets = buckets;
        }
    }

    /** Reads multi-character, all-CJK simplified words with clean pinyin from CC-CEDICT. */
    private static ArrayList<Entry> readCedict(Context context) throws IOException {
        ArrayList<Entry> list = new ArrayList<Entry>(130000);
        BufferedReader reader = null;
        try {
            InputStream in = context.getAssets().open(CEDICT_ASSET);
            reader = new BufferedReader(new InputStreamReader(in, "UTF-8"), 64 * 1024);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int s1 = line.indexOf(' ');
                if (s1 <= 0) continue;
                int s2 = line.indexOf(' ', s1 + 1);
                if (s2 < 0) continue;

                String simp = line.substring(s1 + 1, s2);
                if (simp.length() < 2 || simp.length() > 15 || !allCjk(simp)) continue;

                int lb = line.indexOf('[', s2);
                if (lb < 0) continue;
                int rb = line.indexOf(']', lb + 1);
                if (rb < 0) continue;

                String[] toks = line.substring(lb + 1, rb).split(" ");
                if (toks.length != simp.length()) continue;

                StringBuilder joined = new StringBuilder();
                byte[] lens = new byte[toks.length];
                boolean ok = true;
                for (int i = 0; i < toks.length; i++) {
                    String syl = normalizeSyllable(toks[i]);
                    if (syl == null) {
                        ok = false;
                        break;
                    }
                    lens[i] = (byte) syl.length();
                    joined.append(syl);
                }
                if (!ok) continue;

                list.add(new Entry(simp, joined.toString(), lens));
            }
        } finally {
            closeQuietly(reader);
        }
        return list;
    }

    /**
     * One pass over jieba's dict.txt ("word freq pos" per line). Keeps frequencies for the
     * CEDICT words we care about and for every single CJK character.
     */
    private static Freqs readFrequencies(Context context, ArrayList<Entry> cands) {
        Freqs fr = new Freqs();

        HashSet<String> wanted = new HashSet<String>(cands.size() * 2);
        for (Entry e : cands) {
            wanted.add(e.word);
        }

        BufferedReader reader = null;
        try {
            InputStream in = context.getAssets().open(FREQ_ASSET);
            reader = new BufferedReader(new InputStreamReader(in, "UTF-8"), 64 * 1024);
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) {
                    first = false;
                    if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
                        line = line.substring(1);
                    }
                }
                int s1 = line.indexOf(' ');
                if (s1 <= 0) continue;
                String w = line.substring(0, s1);

                boolean isChar = (w.length() == 1 && isCjk(w.charAt(0)));
                if (!isChar && !wanted.contains(w)) continue;

                int s2 = line.indexOf(' ', s1 + 1);
                String num = (s2 < 0 ? line.substring(s1 + 1) : line.substring(s1 + 1, s2)).trim();
                int f;
                try {
                    f = Integer.parseInt(num);
                } catch (NumberFormatException ex) {
                    continue;
                }

                if (isChar) {
                    Character ch = Character.valueOf(w.charAt(0));
                    Integer old = fr.chars.get(ch);
                    if (old == null || f > old) fr.chars.put(ch, f);
                } else {
                    Integer old = fr.words.get(w);
                    if (old == null || f > old) fr.words.put(w, f);
                }
            }
            fr.have = true;
        } catch (IOException e) {
            Log.w(TAG, "No usable " + FREQ_ASSET + " in assets - ranking will be poor", e);
        } finally {
            closeQuietly(reader);
        }
        return fr;
    }

    /** Attaches frequencies and drops missing/rare words (or keeps all if there's no freq file). */
    private static ArrayList<Entry> filterByFrequency(ArrayList<Entry> cands, Freqs fr) {
        ArrayList<Entry> kept = new ArrayList<Entry>(cands.size());
        HashSet<String> seen = new HashSet<String>();
        for (Entry e : cands) {
            if (fr.have) {
                Integer f = fr.words.get(e.word);
                if (f == null || f < MIN_FREQ) continue;
                e.freq = f;
            } else {
                e.freq = 1;
            }
            // Same word + same reading can appear in more than one CEDICT line.
            if (!seen.add(e.word + "|" + e.joined)) continue;
            kept.add(e);
        }
        return kept;
    }

    private static Entry[][] buildBuckets(ArrayList<Entry> entries) {
        ArrayList<ArrayList<Entry>> lists = new ArrayList<ArrayList<Entry>>(26);
        for (int i = 0; i < 26; i++) {
            lists.add(new ArrayList<Entry>());
        }
        for (Entry e : entries) {
            lists.get(e.joined.charAt(0) - 'a').add(e);
        }

        Comparator<Entry> byFreq = new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.freq != b.freq) return b.freq - a.freq;          // higher frequency first
                return a.joined.length() - b.joined.length();          // then shorter
            }
        };

        Entry[][] buckets = new Entry[26][];
        for (int i = 0; i < 26; i++) {
            ArrayList<Entry> l = lists.get(i);
            Collections.sort(l, byFreq);
            buckets[i] = l.toArray(new Entry[l.size()]);
        }
        return buckets;
    }

    // ---------------------------------------------------------------- helpers

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    private static boolean allCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!isCjk(s.charAt(i))) return false;
        }
        return true;
    }

    private static void closeQuietly(BufferedReader reader) {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** "fei1" -> "fei", "nu:3" -> "nv", "r5" -> "r". Returns null if the token isn't plain pinyin. */
    private static String normalizeSyllable(String tok) {
        int end = tok.length();
        while (end > 0 && Character.isDigit(tok.charAt(end - 1))) {
            end--;
        }
        if (end == 0) return null;
        String s = tok.substring(0, end).toLowerCase(Locale.ROOT).replace("u:", "v");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 'a' || c > 'z') return null;
        }
        return s;
    }
}
