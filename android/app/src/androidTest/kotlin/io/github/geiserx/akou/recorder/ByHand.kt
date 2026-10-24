// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.recorder

/**
 * A test that needs the host to act (place a phone call) and runs only by hand, through
 * `am instrument -e class ...`. The connected run leaves it out (`notAnnotation` in build.gradle.kts).
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class ByHand
