package com.aaron.breeze;

import org.junit.Test;
import static org.junit.Assert.*;

public class BedtimeVoiceCommandTest {
    private static void feeling(String phrase, String state, int intensity) {
        BedtimeVoiceCommand command = BedtimeVoiceCommand.parse(phrase);
        assertEquals(phrase, BedtimeVoiceCommand.Kind.FEELING, command.kind);
        assertEquals(phrase, state, command.state);
        assertEquals(phrase, intensity, command.intensity);
    }

    private static void kind(String phrase, BedtimeVoiceCommand.Kind expected) {
        BedtimeVoiceCommand command = BedtimeVoiceCommand.parse(phrase);
        assertEquals(phrase, expected, command.kind);
        assertNull(phrase, command.state);
        assertEquals(phrase, 0, command.intensity);
    }

    @Test public void acceptsEveryNumberWithBothWakeFormsAndOptionalWords() {
        String[] names = {"one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten"};
        for (String wake : new String[]{"Breeze ", "hey Breeze "}) {
            for (String log : new String[]{"", "log "}) {
                for (String state : new String[]{"WARM", "COLD"}) {
                    for (String level : new String[]{"", "level "}) {
                        for (int value = 1; value <= 10; value++) {
                            feeling(wake + log + state + " " + level + value, state, value);
                            feeling(wake + log + state + " " + level + names[value - 1], state, value);
                        }
                    }
                }
            }
        }
    }

    @Test public void acceptsTwoSpeechVariationsOnlyInIntensityPosition() {
        for (String state : new String[]{"warm", "cold"}) {
            for (String two : new String[]{"two", "too", "to", "2"}) {
                feeling("Breeze " + state + " " + two, state.equals("warm") ? "WARM" : "COLD", 2);
                feeling("Breeze " + state + " level " + two, state.equals("warm") ? "WARM" : "COLD", 2);
            }
        }
        kind("Breeze to warm", BedtimeVoiceCommand.Kind.INVALID);
        kind("Breeze warm to two", BedtimeVoiceCommand.Kind.INVALID);
        kind("Breeze too level two", BedtimeVoiceCommand.Kind.INVALID);
    }

    @Test public void toleratesCaseWhitespaceAndFinalSentencePunctuation() {
        feeling("  hEy   BrEeZe\tLoG CoLd LEVEL TwO?!  ", "COLD", 2);
        feeling("BREEZE WARM 10.", "WARM", 10);
    }

    @Test public void neverAcceptsOutOfRangeNegativeOrNonIntegerValues() {
        for (String state : new String[]{"warm", "cold"}) {
            for (String value : new String[]{"0", "zero", "11", "eleven", "-4", "minus four",
                    "negative four", "+4", "4.0", "2.5", "02", "fourteen", "twenty", "NaN"}) {
                kind("Breeze " + state + " " + value, BedtimeVoiceCommand.Kind.INVALID);
            }
        }
    }

    @Test public void rejectsMalformedPrefixedCommandsAndExtraWords() {
        for (String phrase : new String[]{"Breeze hot 5", "Breeze 5", "Breeze warm", "Breeze cold",
                "Breeze log", "Breeze log 7", "Breeze warm level", "Breeze cold level level two",
                "Breeze warm 7 please", "Breeze warm two cold three", "Breeze log log warm seven",
                "Breeze level warm two", "Breeze stop", "Breeze stop listening now", "Breeze [unk]",
                "hey Breeze turn on the fan", "Breeze warm, two", "Breeze warm 2? extra"}) {
            kind(phrase, BedtimeVoiceCommand.Kind.INVALID);
        }
    }

    @Test public void ignoresCommandsAndConversationWithoutLeadingWakePhrase() {
        for (String phrase : new String[]{null, "", " ", "warm 7", "cold two", "log warm 1",
                "Hey Google log warm seven", "please Breeze warm two", "I said Breeze cold two",
                "the breeze feels warm", "breezewarm two", "hey", "stop listening", "[unk]"}) {
            kind(phrase, BedtimeVoiceCommand.Kind.IGNORE);
        }
    }

    @Test public void distinguishesWakeOnlyAndStopFromFeelingCommands() {
        kind("Breeze", BedtimeVoiceCommand.Kind.READY);
        kind("HEY BREEZE!", BedtimeVoiceCommand.Kind.READY);
        kind("Breeze stop listening", BedtimeVoiceCommand.Kind.STOP);
        kind("hey Breeze stop listening.", BedtimeVoiceCommand.Kind.STOP);
    }

    @Test public void grammarIncludesValidCommandsRejectionExamplesAndUnknownSpeech() {
        String grammar = BedtimeVoiceCommand.grammarJson();
        assertTrue(grammar.startsWith("[\""));
        assertTrue(grammar.endsWith("\"]"));
        for (String phrase : new String[]{"breeze", "hey breeze", "breeze stop listening",
                "hey breeze log warm level two", "breeze cold to", "breeze cold too",
                "breeze warm one", "breeze warm ten", "breeze cold one", "breeze cold ten",
                "breeze hot five", "breeze warm zero", "breeze cold eleven",
                "breeze warm minus four", "breeze cold negative four", "[unk]"}) {
            assertTrue(phrase, grammar.contains("\"" + phrase + "\""));
        }
    }

    @Test public void everySpokenGrammarEntryIsValidatedWithoutThrowing() {
        String grammar = BedtimeVoiceCommand.grammarJson();
        for (String phrase : grammar.substring(2, grammar.length() - 2).split("\",\"")) {
            BedtimeVoiceCommand command = BedtimeVoiceCommand.parse(phrase);
            if (phrase.contains(" hot ") || phrase.endsWith(" hot") || phrase.endsWith(" zero")
                    || phrase.endsWith(" eleven") || phrase.contains(" minus ")
                    || phrase.contains(" negative ")) {
                assertEquals(phrase, BedtimeVoiceCommand.Kind.INVALID, command.kind);
            }
            if (command.kind == BedtimeVoiceCommand.Kind.FEELING) {
                assertTrue(command.intensity >= 1 && command.intensity <= 10);
                assertTrue(command.state.equals("WARM") || command.state.equals("COLD"));
            }
        }
    }

    @Test public void confirmationWordsMatchIntensities() {
        assertEquals("one", BedtimeVoiceCommand.intensityWord(1));
        assertEquals("two", BedtimeVoiceCommand.intensityWord(2));
        assertEquals("ten", BedtimeVoiceCommand.intensityWord(10));
        for (int value : new int[]{0, 11, -4}) {
            try {
                BedtimeVoiceCommand.intensityWord(value);
                fail("Invalid intensity accepted: " + value);
            } catch (IllegalArgumentException expected) { }
        }
    }
}
