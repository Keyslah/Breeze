package com.aaron.breeze;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Strict wake-prefixed commands for silent bedside feeling logging. */
public final class BedtimeVoiceCommand {
    public enum Kind { IGNORE, INVALID, READY, STOP, FEELING }

    public final Kind kind;
    public final String state;
    public final int intensity;

    private static final String[] NUMBER_WORDS = {
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten"
    };

    private BedtimeVoiceCommand(Kind kind, String state, int intensity) {
        this.kind = kind;
        this.state = state;
        this.intensity = intensity;
    }

    private static BedtimeVoiceCommand result(Kind kind) {
        return new BedtimeVoiceCommand(kind, null, 0);
    }

    /** A completed utterance is required. This method never saves an entry. */
    public static BedtimeVoiceCommand parse(String utterance) {
        if (utterance == null) return result(Kind.IGNORE);
        String normalized = utterance.trim().toLowerCase(Locale.ROOT)
                .replaceFirst("[.!?]+$", "").trim();
        if (normalized.isEmpty()) return result(Kind.IGNORE);
        String[] words = normalized.split("\\s+");
        int at;
        if (words[0].equals("breeze")) at = 1;
        else if (words.length >= 2 && words[0].equals("hey") && words[1].equals("breeze")) at = 2;
        else return result(Kind.IGNORE);

        if (at == words.length) return result(Kind.READY);
        if (words.length - at == 2 && words[at].equals("stop") && words[at + 1].equals("listening")) {
            return result(Kind.STOP);
        }
        if (words[at].equals("log")) at++;
        if (at == words.length) return result(Kind.INVALID);
        String state = words[at++];
        if (!state.equals("warm") && !state.equals("cold")) return result(Kind.INVALID);
        if (at < words.length && words[at].equals("level")) at++;
        if (words.length - at != 1) return result(Kind.INVALID);
        int intensity = intensity(words[at]);
        if (intensity == 0) return result(Kind.INVALID);
        return new BedtimeVoiceCommand(Kind.FEELING, state.toUpperCase(Locale.ROOT), intensity);
    }

    private static int intensity(String word) {
        // Aliases only reach this method after a valid wake prefix and state.
        return switch (word) {
            case "1", "one" -> 1;
            case "2", "two", "to", "too" -> 2;
            case "3", "three" -> 3;
            case "4", "four" -> 4;
            case "5", "five" -> 5;
            case "6", "six" -> 6;
            case "7", "seven" -> 7;
            case "8", "eight" -> 8;
            case "9", "nine" -> 9;
            case "10", "ten" -> 10;
            default -> 0;
        };
    }

    public static String intensityWord(int intensity) {
        if (intensity < 1 || intensity > 10) throw new IllegalArgumentException("Intensity must be 1 to 10.");
        return NUMBER_WORDS[intensity - 1];
    }

    /**
     * Spoken-word grammar for Vosk. Digits are accepted by parse(), but grammar
     * entries use acoustic-model words. Invalid examples and [unk] reduce the
     * pressure to decode unrelated speech as a valid command; they cannot make
     * speech recognition infallible. Always validate the final result with parse().
     */
    public static String grammarJson() {
        Set<String> phrases = new LinkedHashSet<>();
        for (String wake : new String[]{"breeze", "hey breeze"}) {
            phrases.add(wake);
            phrases.add(wake + " stop listening");
            for (String log : new String[]{"", "log "}) {
                String lead = wake + " " + log;
                if (!log.isEmpty()) phrases.add(lead.trim());
                for (String state : new String[]{"warm", "cold", "hot"}) {
                    String command = lead + state;
                    phrases.add(command);
                    for (String level : new String[]{"", "level "}) {
                        String valueLead = command + " " + level;
                        if (!level.isEmpty()) phrases.add(valueLead.trim());
                        for (String word : NUMBER_WORDS) phrases.add(valueLead + word);
                        phrases.add(valueLead + "to");
                        phrases.add(valueLead + "too");
                        phrases.add(valueLead + "zero");
                        phrases.add(valueLead + "eleven");
                        for (String word : NUMBER_WORDS) {
                            phrases.add(valueLead + "minus " + word);
                            phrases.add(valueLead + "negative " + word);
                        }
                    }
                }
            }
        }
        phrases.add("[unk]");
        // All generated phrases contain only constant lowercase words and spaces.
        return "[\"" + String.join("\",\"", phrases) + "\"]";
    }
}
