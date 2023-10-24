package edu.duke.bartesaghi.micromon.mongo

import org.bson.Document


/**
 * These seem not to be built-into the MongoDB client library for some reason
 * See: https://www.mongodb.com/docs/manual/meta/aggregation-quick-reference/#operator-expressions
 */
object AggregationOperator {

	object Array {

		fun size(fieldName: String) =
			Document().apply {
				this["\$size"] = "\$$fieldName"
			}
	}
}
