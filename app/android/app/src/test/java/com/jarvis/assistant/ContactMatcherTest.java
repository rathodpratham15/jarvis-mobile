package com.jarvis.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ContactMatcherTest {
    private static ContactMatcher.Contact c(long id, String n) { return new ContactMatcher.Contact(id, n, "1"); }

    private final List<ContactMatcher.Contact> all = Arrays.asList(
            c(1, "Badi Mummy"), c(2, "Mummy"), c(3, "Piyush Mummy"), c(4, "Dad"), c(5, "Rohan Shah"), c(6, "Rohan Mehta"));

    @Test public void exactBeatsPartial() {
        List<String> n = ContactMatcher.distinctNames(ContactMatcher.match("mummy", all));
        assertEquals(Arrays.asList("Mummy"), n);
    }

    @Test public void nicknameGroupFindsMummyForMom() {
        assertEquals(Arrays.asList("Mummy"), ContactMatcher.distinctNames(ContactMatcher.match("mom", all)));
    }

    @Test public void papaFindsDad() {
        assertEquals(Arrays.asList("Dad"), ContactMatcher.distinctNames(ContactMatcher.match("papa", all)));
    }

    @Test public void partialWholeWordWhenNoExact() {
        assertEquals(Arrays.asList("Piyush Mummy"), ContactMatcher.distinctNames(ContactMatcher.match("piyush", all)));
    }

    @Test public void ambiguousSurnamesReturnBoth() {
        assertEquals(Arrays.asList("Rohan Shah", "Rohan Mehta"), ContactMatcher.distinctNames(ContactMatcher.match("rohan", all)));
    }

    @Test public void noMatchIsEmpty() { assertTrue(ContactMatcher.match("zzz", all).isEmpty()); }

    @Test public void literalBeatsNickname() {
        List<ContactMatcher.Contact> l = Arrays.asList(c(1, "Mom"), c(2, "Mummy"));
        assertEquals(Arrays.asList("Mom"), ContactMatcher.distinctNames(ContactMatcher.match("mom", l)));
    }

    @Test public void duplicateRowsOfSamePersonAreNotAmbiguous() {
        List<ContactMatcher.Contact> l = Arrays.asList(c(1, "Mummy"), c(2, "Mummy"));
        assertEquals(1, ContactMatcher.distinctNames(ContactMatcher.match("mummy", l)).size());
    }
}
