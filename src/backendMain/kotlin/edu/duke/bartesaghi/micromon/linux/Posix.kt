package edu.duke.bartesaghi.micromon.linux

import edu.duke.bartesaghi.micromon.assertEq


object Posix {

	fun tokenize(str: String): List<String> {

		val tokens = ArrayList<String>()

		var quoting = false
		var quote = '\u0000'
		var escaping = false
		var posCloseQuote: Int? = null
		var token = StringBuilder()

		fun isValidEscape(nextc: Char?) =
			when (quote) {
				'\u0000' -> true // not quoting, all valid
				'"' -> nextc in listOf('\\', '\"') // double quotes, only \ and " allowed
				'\'' -> false // no valid escapes in single-quoted strings
				else -> throw Error("invalid quote character: $quote")
			}

		for (i in str.indices) {
			val c = str[i]
			val nextc = str.getOrNull(i + 1)

			if (escaping) {

				// escaped character, consume the character and reset escaping
				escaping = false
				token.append(c)

			} else if (c == '\\' && isValidEscape(nextc)) {

				// escape character, turn on escaping for valid escape sequences
				escaping = true

			} else if (!quoting && (c == '"' || c == '\'')) {

				// quote character, turn on quoting
				quoting = true
				quote = c

			} else if (quoting && c == quote) {

				// end quote, turn off quoting
				quoting = false
				quote = '\u0000'
				posCloseQuote = i

			} else if (!quoting && Character.isWhitespace(c)) {

				// unquoted whitespace, end the token
				if (token.isNotEmpty() || posCloseQuote == (i - 1)) {
					tokens.add(token.toString())
					token = StringBuilder()
				}

			} else {
				// unquoted unescaped character, consume it
				token.append(c)
			}
		}

		// end the current token, if any
		if (token.isNotEmpty() || posCloseQuote == (str.length - 1)) {
			tokens.add(token.toString())
		}

		return tokens
	}
}


// unit tests
fun main() {

	// test suite from Andrey Kuzubov's shlex Kotlin library:
	// https://github.com/klee0kai/shlex/blob/dev/shlex/src/test/kotlin/com/github/klee0kai/shlex/test/ShlexTests.kt
	val posixTests = """
x|x|
foo bar|foo|bar|
 foo bar|foo|bar|
 foo bar |foo|bar|
foo   bar    bla     fasel|foo|bar|bla|fasel|
x y  z              xxxx|x|y|z|xxxx|
\x bar|x|bar|
\ x bar| x|bar|
\ bar| bar|
foo \x bar|foo|x|bar|
foo \ x bar|foo| x|bar|
foo \ bar|foo| bar|
foo "bar" bla|foo|bar|bla|
"foo" "bar" "bla"|foo|bar|bla|
"foo" bar "bla"|foo|bar|bla|
"foo" bar bla|foo|bar|bla|
foo 'bar' bla|foo|bar|bla|
'foo' 'bar' 'bla'|foo|bar|bla|
'foo' bar 'bla'|foo|bar|bla|
'foo' bar bla|foo|bar|bla|
blurb foo"bar"bar"fasel" baz|blurb|foobarbarfasel|baz|
blurb foo'bar'bar'fasel' baz|blurb|foobarbarfasel|baz|
""||
''||
foo "" bar|foo||bar|
foo '' bar|foo||bar|
foo "" "" "" bar|foo||||bar|
foo '' '' '' bar|foo||||bar|
\"|"|
"\""|"|
"foo\ bar"|foo\ bar|
"foo\\ bar"|foo\ bar|
"foo\\ bar\""|foo\ bar"|
"foo\\" bar\"|foo\|bar"|
"foo\\ bar\" dfadf"|foo\ bar" dfadf|
"foo\\\ bar\" dfadf"|foo\\ bar" dfadf|
"foo\\\x bar\" dfadf"|foo\\x bar" dfadf|
"foo\x bar\" dfadf"|foo\x bar" dfadf|
\'|'|
'foo\ bar'|foo\ bar|
'foo\\ bar'|foo\\ bar|
"foo\\\x bar\" df'a\ 'df"|foo\\x bar" df'a\ 'df|
\"foo|"foo|
\"foo\x|"foox|
"foo\x"|foo\x|
"foo\ "|foo\ |
foo\ xx|foo xx|
foo\ x\x|foo xx|
foo\ x\x\"|foo xx"|
"foo\ x\x"|foo\ x\x|
"foo\ x\x\\"|foo\ x\x\|
"foo\ x\x\\""foobar"|foo\ x\x\foobar|
"foo\ x\x\\"\'"foobar"|foo\ x\x\'foobar|
"foo\ x\x\\"\'"fo'obar"|foo\ x\x\'fo'obar|
"foo\ x\x\\"\'"fo'obar" 'don'\''t'|foo\ x\x\'fo'obar|don't|
"foo\ x\x\\"\'"fo'obar" 'don'\''t' \\|foo\ x\x\'fo'obar|don't|\|
'foo\ bar'|foo\ bar|
'foo\\ bar'|foo\\ bar|
foo\ bar|foo bar|
:-) ;-)|:-)|;-)|
áéíóú|áéíóú|
	"""

	var numPassed = 0
	var numFailed = 0
	for (line in posixTests.lineSequence()) {

		if (line.isBlank()) {
			continue
		}

		val parts = line.split("|")
		val input = parts[0]
		val tokens = parts.subList(1, parts.size - 1)

		try {
			assertEq(Posix.tokenize(input), tokens)
			numPassed += 1
		} catch (t: Throwable) {
			println("FAILED: $input\n$t")
			numFailed += 1
		}
	}

	println("Passed $numPassed tests, Failed $numFailed!")
}
