package dev.optimus.core;

import dev.optimus.core.combat.KillFeed;

/** Exact server messages, dynamic bot identity and duplicate suppression. */
public final class KillFeedSelfTest {
    private static int checks;

    private static void expect(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }

    public static void main(String[] args) {
        KillFeed feed = new KillFeed();
        KillFeed.Outcome death =
                feed.accept("rocke1 was killed by rocket770! 1 KILL STREAK!", "rocke1", 1);
        expect(
                death != null && death.death && death.other.equals("rocket770"),
                "victim identity means the bot died");
        KillFeed.Outcome kill =
                feed.accept("rocket770 was killed by rocke1! 1 KILL STREAK!", "rocke1", 2);
        expect(
                kill != null && !kill.death && kill.other.equals("rocket770"),
                "killer identity means the bot earned a kill");
        expect(
                feed.accept("rocket770 was killed by rocke1! 1 KILL STREAK!", "rocke1", 3) == null,
                "duplicate feed line does not add another kill");
        expect(
                feed.accept("rocket770 was killed by rocke1! 2 KILL STREAK!", "rocke1", 3) != null,
                "different streak is a distinct rapid outcome");
        expect(
                feed.accept("Other_One was killed by Bot_Two!", "bot_two", 4) != null,
                "bot account is supplied dynamically and matched case-insensitively");
        expect(
                feed.accept("Some_Other was killed by Bot_Two!", "Bot", 5) == null,
                "names are exact rather than prefix matches");
        for (String line :
                new String[] {
                    "Main_Name: rocke1 was killed by rocket770! 1 KILL STREAK!",
                    "[VIP] Main_Name: rocket770 was killed by rocke1!",
                    "From Main_Name: rocke1 was killed by rocket770!",
                    "rocke1 was killed by rocket770!\n",
                    "rocke1 was killed by rocket770! and more text",
                    "Other_One was killed by Other_Two!",
                    "rocke1 was killed by rocket770",
                    "rocke1 was killed by /kill!"
                })
            expect(
                    feed.accept(line, "rocke1", 60) == null,
                    "reject quoted, unrelated or malformed feed: " + line);
        expect(
                feed.accept(null, "rocke1", 61) == null
                        && feed.accept("rocke1 was killed by rocket770!", null, 61) == null,
                "missing text or account is ignored");
        feed.reset();
        expect(
                feed.accept("rocket770 was killed by rocke1! 1 KILL STREAK!", "rocke1", 3) != null,
                "new recording/run can clear duplicate history");
        System.out.println(
                "PASS: " + checks + " kill-feed identity, spoofing and duplicate checks");
    }
}