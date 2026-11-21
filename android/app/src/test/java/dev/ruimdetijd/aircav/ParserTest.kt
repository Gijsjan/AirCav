package dev.ruimdetijd.aircav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserTest {
    @Test
    fun plainTextBecomesAdd() {
        val cmd = parse("Put on the kettle")
        assertTrue(cmd is Command.Add)
        cmd as Command.Add
        assertEquals("Put on the kettle", cmd.description)
        assertTrue(cmd.tags.isEmpty())
        assertEquals("", cmd.project)
    }

    @Test
    fun taskAddPrefix() {
        val cmd = parse("task add buy milk")
        assertTrue(cmd is Command.Add)
        cmd as Command.Add
        assertEquals("buy milk", cmd.description)
    }

    @Test
    fun taskAddWithTagsAndProject() {
        val cmd = parse("task add buy milk +errands project:home")
        cmd as Command.Add
        assertEquals("buy milk", cmd.description)
        assertEquals(listOf("errands"), cmd.tags)
        assertEquals("home", cmd.project)
    }

    @Test
    fun spokenProjectTwoTokensIsNormalized() {
        // spoken transcript: capitalized, sentence punctuation
        val cmd = parse("Nog een kopje thee zetten alsjeblieft. Project Home.")
        cmd as Command.Add
        assertEquals("Nog een kopje thee zetten alsjeblieft", cmd.description)
        assertEquals("home", cmd.project)
    }

    @Test
    fun deleteCommandWithPunctuation() {
        // spoken transcript: "Task 35, delete."
        val cmd = parse("Task 35, delete.")
        assertTrue(cmd is Command.Act)
        cmd as Command.Act
        assertEquals(35, cmd.number)
        assertEquals("delete", cmd.action)
    }

    @Test
    fun doneCommandDutch() {
        val cmd = parse("taak 12 verwijderen")
        cmd as Command.Act
        assertEquals(12, cmd.number)
        assertEquals("delete", cmd.action)
        val done = parse("task 12 done")
        done as Command.Act
        assertEquals("done", done.action)
    }

    @Test
    fun unprefixedVerbTextIsAnAddNeverACommand() {
        // regression: a task literally called "verwijder oude troep" must not
        // be eaten by the command parser
        val cmd = parse("verwijder oude troep")
        assertTrue(cmd is Command.Add)
    }

    @Test
    fun emptyAndPunctuationOnlyIsNull() {
        assertNull(parse("..."))
        assertNull(parse(""))
    }

    @Test
    fun atSignTagAlsoWorks() {
        val cmd = parse("fix the sink @home")
        cmd as Command.Add
        assertEquals(listOf("home"), cmd.tags)
        assertEquals("fix the sink", cmd.description)
    }

    @Test
    fun unprefixedNumberVerbIsAnAdd() {
        // "35 delete" without the task prefix must stay a task, never a command
        val cmd = parse("35 delete")
        assertTrue(cmd is Command.Add)
        cmd as Command.Add
        assertEquals("35 delete", cmd.description)
    }

    @Test
    fun projectHierarchyMatch() {
        assertTrue(matchesProject("home", "home"))
        assertTrue(matchesProject("home.klussen", "home"))
        assertTrue(matchesProject("Home.Klussen", "HOME"))
        assertTrue(matchesProject("home.klussen.garage", "home"))
        org.junit.Assert.assertFalse(matchesProject("homework", "home")) // no partial-word match
        org.junit.Assert.assertFalse(matchesProject(null, "home"))
        org.junit.Assert.assertFalse(matchesProject("nde", "home"))
    }
}
