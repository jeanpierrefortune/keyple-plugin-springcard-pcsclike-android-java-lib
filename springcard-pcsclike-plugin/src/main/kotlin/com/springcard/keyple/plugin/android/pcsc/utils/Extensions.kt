/* **************************************************************************************
 * Copyright (c) 2022 SpringCard - https://www.springcard.com/
 *
 * See the NOTICE file(s) distributed with this work for additional information
 * regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the terms of the
 * Eclipse Public License 2.0 which is available at http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 ************************************************************************************** */
package com.springcard.keyple.plugin.android.pcsc.utils

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

suspend inline fun <T> suspendCoroutineWithTimeout(
    timeout: Long,
    crossinline block: (CancellableContinuation<T>) -> Unit
): T? {
  var finalValue: T? = null
  withTimeoutOrNull(timeout) { finalValue = suspendCancellableCoroutine(block = block) }
  return finalValue
}
