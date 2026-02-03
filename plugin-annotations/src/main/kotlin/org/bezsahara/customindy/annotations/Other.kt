package org.bezsahara.customindy.annotations

public class IndyNotImplementedException(message: String? = null) : RuntimeException(message)

public inline fun throwIndyNotImplemented(message: String? = null): Nothing { throw IndyNotImplementedException(message) }