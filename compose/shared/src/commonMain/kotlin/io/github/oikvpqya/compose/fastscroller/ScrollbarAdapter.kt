/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.oikvpqya.compose.fastscroller

// Modified by Finance Tracker: this common contract replaces unused upstream expect/actual adapters.
/**
 * Maps a scrollable UI element to the range used by a FastScroller.
 *
 * The values do not have to be pixels. They can represent any ordered range,
 * such as transaction ordinals in a filtered ledger.
 */
interface ScrollbarAdapter {

    /** Current content offset on the scrollable axis. */
    val scrollOffset: Double

    /** Total size of the scrollable content. */
    val contentSize: Double

    /** Visible size of the content viewport. */
    val viewportSize: Double

    /** Instantly jump to the requested offset, clamped to the valid range. */
    suspend fun scrollTo(scrollOffset: Double)
}
