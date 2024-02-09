package edu.duke.bartesaghi.micromon.services


/**
 * This type is a string, but contains another type in serialized from.
 *
 * To access the inner type, the string should be deserialized,
 * usually by calling `T.deserialize()` where `T` is the inner type.
 *
 * Likewise, you can usually serialize a value by calling the `instance.serialize()`
 * where `instance` is an instance of type `T`.
 *
 * Usually, the extra layer of serialization in a service function is needed
 * to allow polymorphic serialization/deserialization when KVision's serialization
 * system wouldn't normally support it.
 */
typealias Serialized<T> = String
