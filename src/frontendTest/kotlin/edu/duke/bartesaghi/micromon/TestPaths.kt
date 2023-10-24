package edu.duke.bartesaghi.micromon

import io.kvision.test.SimpleSpec
import kotlin.test.Test
import kotlin.test.assertEquals


class TestPaths : SimpleSpec {

	@Test
	fun popPath() {
		run {
			assertEquals(null to "", "".popPath())

			assertEquals(null to "/", "/".popPath())

			assertEquals(null to "foo", "foo".popPath())
			assertEquals(null to "foo/", "foo/".popPath())
			assertEquals("/" to "foo", "/foo".popPath())
			assertEquals("/" to "foo/", "/foo/".popPath())

			assertEquals("foo" to "bar", "foo/bar".popPath())
			assertEquals("foo" to "bar/", "foo/bar/".popPath())
			assertEquals("/foo" to "bar", "/foo/bar".popPath())
			assertEquals("/foo" to "bar/", "/foo/bar/".popPath())
		}
	}

	@Test
	fun globMatcher() {
		run {
			// regular glob stuff
			assertEquals(true, GlobMatcher("").matches(""))
			assertEquals(false, GlobMatcher("").matches("a"))

			assertEquals(true, GlobMatcher("a").matches("a"))
			assertEquals(false, GlobMatcher("a").matches("b"))

			assertEquals(true, GlobMatcher("*").matches(""))
			assertEquals(true, GlobMatcher("*").matches("a"))
			assertEquals(true, GlobMatcher("*").matches("b"))

			assertEquals(true, GlobMatcher("a*").matches("a"))
			assertEquals(true, GlobMatcher("a*").matches("aa"))
			assertEquals(true, GlobMatcher("a*").matches("ab"))
			assertEquals(false, GlobMatcher("a*").matches("ba"))
			assertEquals(false, GlobMatcher("a*").matches("bb"))
			assertEquals(false, GlobMatcher("a*").matches(""))

			assertEquals(true, GlobMatcher("*a").matches("a"))
			assertEquals(true, GlobMatcher("*a").matches("aa"))
			assertEquals(false, GlobMatcher("*a").matches("ab"))
			assertEquals(true, GlobMatcher("*a").matches("ba"))
			assertEquals(false, GlobMatcher("*a").matches("bb"))
			assertEquals(false, GlobMatcher("*a").matches(""))

			// chars that need escaping in the regex
			assertEquals(false, GlobMatcher(".").matches("a"))
			assertEquals(false, GlobMatcher(".").matches(""))
			assertEquals(true, GlobMatcher(".").matches("."))

			assertEquals(false, GlobMatcher("a.*").matches("a"))
			assertEquals(true, GlobMatcher("a.*").matches("a."))
			assertEquals(true, GlobMatcher("a.*").matches("a.a"))
			assertEquals(true, GlobMatcher("a.*").matches("a.b"))
			assertEquals(false, GlobMatcher("a.*").matches("aaa"))
		}
	}
}
