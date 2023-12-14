package edu.duke.bartesaghi.micromon.dokka


private enum class Case {
	None,
	Lower,
	Upper
}

private fun Char.case(): Case =
	if (isLowerCase()) {
		Case.Lower
	} else if (isUpperCase()) {
		Case.Upper
	} else {
		Case.None
	}


fun String.caseSplitCamel(): List<String> {

	if (isEmpty()) {
		return emptyList()
	}

	val words = ArrayList<String>()

	var lastcase: Case = this[0].case()
	var lasti = 0
	for ((i, c) in withIndex()) {

		// add a new word on lower->upper case changes
		val case = c.case()
		if (lastcase == Case.Lower && case == Case.Upper) {
			words.add(substring(lasti until i))
			lasti = i
		}
		lastcase = case
	}

	// add the last word
	words.add(substring(lasti))

	return words
}

fun String.caseCamelToSnake(): String =
	caseSplitCamel()
		.map { it.lowercase() }
		.joinToString("_")
