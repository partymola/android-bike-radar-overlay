// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 JJ del Rio
// Permissive so another app can copy this contract into its own build.
// Licence text: LICENSES/Apache-2.0.txt. Consumers: additional-permission.txt
package es.jjrh.bikeradar.ipc;

// Declares the Kotlin Parcelable to AIDL. The layout itself lives in
// RadarStateParcel.kt, which is the single source of truth for it.
parcelable RadarStateParcel;
